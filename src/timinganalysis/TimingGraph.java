package timinganalysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Stack;
import java.util.Vector;

import architecture.SiteType;

import placers.SAPlacer.Swap;

import circuit.Ble;
import circuit.Block;
import circuit.BlockType;
import circuit.Clb;
import circuit.Flipflop;
import circuit.HardBlock;
import circuit.Input;
import circuit.Lut;
import circuit.Net;
import circuit.PackedCircuit;
import circuit.Pin;
import circuit.PrePackedCircuit;

public class TimingGraph
{

	//private static final double MHD_DELAY = 0.5;
	//private static final double LUT_DELAY = 1.0;
	
	private static final double MHD_DELAY = 0.005;
	private static final double LUT_DELAY = 0.01;
	
	private PrePackedCircuit circuit;
	private List<TimingNode> startNodes;
	private List<TimingNode> endNodes;
	private Map<Block,ArrayList<TimingNode>> blockMap; //Maps a block to all its associated timingnodes
	private ArrayList<TimingEdge> edges; //Contains all edges present in the timing graph
	private double maxDelay;
	private double criticalityExponent;
	private LinkedList<TimingEdge> affectedEdgeList; //Collects the timingEdge objects who's delay still needs to be pushed through or reverted
	private boolean clbsMapped;
	private Map<Pin,TimingNode> clbPinMap;
	private Map<Net,ArrayList<TimingEdge>> edgeMap;
	
	public TimingGraph(PrePackedCircuit circuit)
	{
		this.circuit = circuit;
		startNodes = new ArrayList<>();
		endNodes = new ArrayList<>();
		blockMap = new HashMap<>();
		edges = new ArrayList<>();
		maxDelay = 0.0;
		criticalityExponent = 8.0;
		affectedEdgeList = new LinkedList<>();
		clbsMapped = false;
		clbPinMap = null;
		edgeMap = null;
	}
	
	public void buildTimingGraph()
	{
		//Build all trees starting from circuit inputs
		for(Input input:circuit.getInputs().values())
		{
			processStartPin(input.output);
		}
		
		//Build all trees starting from flipflop outputs
		for(Flipflop flipflop:circuit.getFlipflops().values())
		{
			processStartPin(flipflop.getOutput());
		}
		
		//Build all trees starting from a hardBlock which is clocked
		for(Vector<HardBlock> hbVector: circuit.getHardBlocks())
		{
			for(HardBlock hardBlock: hbVector)
			{
				if(hardBlock.type == BlockType.HARDBLOCK_CLOCKED)
				{
					for(Pin source: hardBlock.getOutputs())
					{
						processStartPin(source);
					}
				}
			}
		}
		
		//Calculate arrival and required times (definition: see VPR book)
		recalculateAllSlacksCriticalities();
	}
	
	public double calculateMaximalDelay()
	{
		double maxDelay = 0.0;
		for(TimingNode endNode:endNodes)
		{
			if(endNode.getTArrival() > maxDelay)
			{
				maxDelay = endNode.getTArrival();
			}
		}
		return maxDelay;
	}
	
	public double calculateDeltaCost(Swap swap)
	{
		double deltaCost = 0.0;
		if(swap.pl1.block != null && swap.pl2.block != null)
		{
			if(swap.pl1.type == SiteType.CLB)
			{
				Block block1 = swap.pl1.block;
				Block block2 = swap.pl2.block;
				Lut lut1 = ((Clb)block1).getBle().getLut();
				Lut lut2 = ((Clb)block2).getBle().getLut();
				Flipflop ff1 = ((Clb)block1).getBle().getFlipflop();
				Flipflop ff2 = ((Clb)block2).getBle().getFlipflop();

				//Process source net of first clb
				if(ff1 != null)
				{
					TimingNode sourceNode = null;
					ArrayList<TimingNode> ff1NodeList = blockMap.get(ff1);
					for(int i = 0; i < ff1NodeList.size(); i++)
					{
						if(ff1NodeList.get(i).getType() == TimingNodeType.START_NODE)
						{
							sourceNode = ff1NodeList.get(i);
							break;
						}
					}
					for(TimingEdge connectedEdge: sourceNode.getOutputs())
					{
						Block owner = connectedEdge.getOutput().getPin().owner;
						if(owner != ff2 && owner != lut2 && owner != lut1)
						{
							int mhd = Math.abs(owner.getSite().x - swap.pl2.x) 
									+ Math.abs(owner.getSite().y - swap.pl2.y);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
				}
				else //only a lut in the clb, no ff
				{
					TimingNode sourceNode = null;
					ArrayList<TimingNode> lut1NodeList = blockMap.get(lut1);
					if(lut1NodeList != null)
					{
						for(int i = 0; i < lut1NodeList.size(); i++)
						{
							//WATCH OUT: start node in the case of a source LUT (= a LUT that only sources a net but doesn't sink any nets)
							if(lut1NodeList.get(i).getType() == TimingNodeType.INTERNAL_SOURCE_NODE ||
										lut1NodeList.get(i).getType() == TimingNodeType.START_NODE)
							{
								sourceNode = lut1NodeList.get(i);
							}
						}
						for(TimingEdge connectedEdge: sourceNode.getOutputs())
						{
							Block owner = connectedEdge.getOutput().getPin().owner;
							if(owner != ff2 && owner != lut2) //No need to check for ff1 as this is null
							{
								int mhd = Math.abs(owner.getSite().x - swap.pl2.x)
										+ Math.abs(owner.getSite().y - swap.pl2.y);
								deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
								affectedEdgeList.add(connectedEdge);
							}
						}
					}
				}
				//Process sink nets of first clb
				if(lut1 != null)
				{
					ArrayList<TimingNode> lut1NodeList = blockMap.get(lut1);
					if(lut1NodeList != null)
					{
						for(int i = 0; i < lut1NodeList.size(); i++)
						{
							TimingNode node = lut1NodeList.get(i);
							if(node.getType() == TimingNodeType.INTERNAL_SINK_NODE)
							{
								if(node.getInputs().size() > 1)
								{
									System.err.println("There should only be one input to an internalSinkNode!");
								}
								TimingEdge connectedEdge = node.getInputs().get(0);
								Block owner = connectedEdge.getInput().getPin().owner;
								if(owner != ff2 && owner != lut2 && owner != ff1)
								{
									int mhd = Math.abs(owner.getSite().x - swap.pl2.x)
											+ Math.abs(owner.getSite().y - swap.pl2.y);
									deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
									affectedEdgeList.add(connectedEdge);
								}
							}
						}
					}
				}
				else //only a ff in the clb, no lut
				{
					ArrayList<TimingNode> ff1NodeList = blockMap.get(ff1);
					for(int i = 0; i < ff1NodeList.size(); i++)
					{
						TimingNode node = ff1NodeList.get(i);
						if(node.getType() == TimingNodeType.END_NODE)
						{
							if(node.getInputs().size() > 1)
							{
								System.err.println("There should only be one input to an endNode!");
							}
							TimingEdge connectedEdge = node.getInputs().get(0);
							Block owner = connectedEdge.getInput().getPin().owner;
							if(owner != ff2 && owner != lut2) //No need to check for lut1 as this is null
							{
								int mhd = Math.abs(owner.getSite().x - swap.pl2.x)
										+ Math.abs(owner.getSite().y - swap.pl2.y);
								deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
								affectedEdgeList.add(connectedEdge);
							}
						}
					}
				}
			
				//Repeat the whole process for the second clb
				//Process source net of second clb
				if(ff2 != null)
				{
					TimingNode sourceNode = null;
					ArrayList<TimingNode> ff2NodeList = blockMap.get(ff2);
					for(int i = 0; i < ff2NodeList.size(); i++)
					{
						if(ff2NodeList.get(i).getType() == TimingNodeType.START_NODE)
						{
							sourceNode = ff2NodeList.get(i);
							break;
						}
					}
					for(TimingEdge connectedEdge: sourceNode.getOutputs())
					{
						Block owner = connectedEdge.getOutput().getPin().owner;
						if(owner != ff1 && owner != lut1 && owner != lut2)
						{
							int mhd = Math.abs(owner.getSite().x - swap.pl1.x) 
									+ Math.abs(owner.getSite().y - swap.pl1.y);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
				}
				else //only a lut in the clb, no ff
				{
					TimingNode sourceNode = null;
					ArrayList<TimingNode> lut2NodeList = blockMap.get(lut2);
					if(lut2NodeList != null)
					{
						for(int i = 0; i < lut2NodeList.size(); i++)
						{
							//WATCH OUT: start node in the case of a source LUT (= a LUT that only sources a net but doesn't sink any nets)
							if(lut2NodeList.get(i).getType() == TimingNodeType.INTERNAL_SOURCE_NODE || 
											lut2NodeList.get(i).getType() == TimingNodeType.START_NODE)
							{
								sourceNode = lut2NodeList.get(i);
							}
						}					
						for(TimingEdge connectedEdge: sourceNode.getOutputs())
						{
							Block owner = connectedEdge.getOutput().getPin().owner;
							if(owner != ff1 && owner != lut1) //No need to check for ff2 as this is null
							{
								int mhd = Math.abs(owner.getSite().x - swap.pl1.x)
										+ Math.abs(owner.getSite().y - swap.pl1.y);
								deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
								affectedEdgeList.add(connectedEdge);
							}
						}
					}
				}
				//Process sink nets of first clb
				if(lut2 != null)
				{
					ArrayList<TimingNode> lut2NodeList = blockMap.get(lut2);
					if(lut2NodeList != null)
					{
						for(int i = 0; i < lut2NodeList.size(); i++)
						{
							TimingNode node = lut2NodeList.get(i);
							if(node.getType() == TimingNodeType.INTERNAL_SINK_NODE)
							{
								if(node.getInputs().size() > 1)
								{
									System.err.println("There should only be one input to an internalSinkNode!");
								}
								TimingEdge connectedEdge = node.getInputs().get(0);
								Block owner = connectedEdge.getInput().getPin().owner;
								if(owner != ff1 && owner != lut1 && owner != ff2)
								{
									int mhd = Math.abs(owner.getSite().x - swap.pl1.x)
											+ Math.abs(owner.getSite().y - swap.pl1.y);
									deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
									affectedEdgeList.add(connectedEdge);
								}
							}
						}
					}
				}
				else //only a ff in the clb, no lut
				{
					ArrayList<TimingNode> ff2NodeList = blockMap.get(ff2);
					for(int i = 0; i < ff2NodeList.size(); i++)
					{
						TimingNode node = ff2NodeList.get(i);
						if(node.getType() == TimingNodeType.END_NODE)
						{
							if(node.getInputs().size() > 1)
							{
								System.err.println("There should only be one input to an endNode!");
							}
							TimingEdge connectedEdge = node.getInputs().get(0);
							Block owner = connectedEdge.getInput().getPin().owner;
							if(owner != ff1 && owner != lut1) //No need to check for lut2 as this is null
							{
								int mhd = Math.abs(owner.getSite().x - swap.pl1.x)
										+ Math.abs(owner.getSite().y - swap.pl1.y);
								deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
								affectedEdgeList.add(connectedEdge);
							}
						}
					}
				}
			}
			else if(swap.pl1.type == SiteType.HARDBLOCK)
			{
				HardBlock hb1 = (HardBlock)swap.pl1.block;
				HardBlock hb2 = (HardBlock)swap.pl2.block;
				
				//Process first hardBlock (inputs and outputs)
				ArrayList<TimingNode> hb1NodeList = blockMap.get(hb1);
				for(TimingNode node: hb1NodeList)
				{
					if(node.getType() == TimingNodeType.INTERNAL_SOURCE_NODE || 
									node.getType() == TimingNodeType.START_NODE) //TimingNode represents an output of the hardblock
					{
						for(TimingEdge connectedEdge: node.getOutputs())
						{
							Block owner = connectedEdge.getOutput().getPin().owner;
							if(owner != hb2)
							{
								int mhd = Math.abs(owner.getSite().x - swap.pl2.x)
										+ Math.abs(owner.getSite().y - swap.pl2.y);
								deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
								affectedEdgeList.add(connectedEdge);
							}
						}
					}
					else //TimingNode represents an input of the hardBlock
					{
						if(node.getInputs().size() > 1)
						{
							System.err.println("There should only be one input to an endNode or an internalSinkNode!");
						}
						TimingEdge connectedEdge = node.getInputs().get(0);
						Block owner = connectedEdge.getInput().getPin().owner;
						if(owner != hb2)
						{
							int mhd = Math.abs(owner.getSite().x - swap.pl2.x)
									+ Math.abs(owner.getSite().y - swap.pl2.y);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
				}
				
				//Process second hardBlock (inputs and outputs)
				ArrayList<TimingNode> hb2NodeList = blockMap.get(hb2);
				for(TimingNode node: hb2NodeList)
				{
					if(node.getType() == TimingNodeType.INTERNAL_SOURCE_NODE || 
									node.getType() == TimingNodeType.START_NODE) //TimingNode represents an output of the hardBlock
					{
						for(TimingEdge connectedEdge: node.getOutputs())
						{
							Block owner = connectedEdge.getOutput().getPin().owner;
							if(owner != hb1)
							{
								int mhd = Math.abs(owner.getSite().x - swap.pl1.x)
										+ Math.abs(owner.getSite().y - swap.pl1.y);
								deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
								affectedEdgeList.add(connectedEdge);
							}
						}
					}
					else //TimingNode represents an input of the hardBlock
					{
						if(node.getInputs().size() > 1)
						{
							System.err.println("There should only be one input to an endNode or an internalSinkNode!");
						}
						TimingEdge connectedEdge = node.getInputs().get(0);
						Block owner = connectedEdge.getInput().getPin().owner;
						if(owner != hb1)
						{
							int mhd = Math.abs(owner.getSite().x - swap.pl1.x)
									+ Math.abs(owner.getSite().y - swap.pl1.y);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
				}
			}
		}
		else
		{
			if(swap.pl1.type == SiteType.CLB)
			{
				Lut lut = null;
				Flipflop ff = null;
				int newX = 0;
				int newY = 0;
				if(swap.pl1.block != null)
				{
					if(swap.pl1.block.type == BlockType.CLB)
					{
						lut = ((Clb)swap.pl1.block).getBle().getLut();
						ff = ((Clb)swap.pl1.block).getBle().getFlipflop();
						newX = swap.pl2.x;
						newY = swap.pl2.y;
					}
				}
				else
				{
					if(swap.pl2.block.type == BlockType.CLB)
					{
						lut = ((Clb)swap.pl2.block).getBle().getLut();
						ff = ((Clb)swap.pl2.block).getBle().getFlipflop();
						newX = swap.pl1.x;
						newY = swap.pl1.y;
					}
				}
				//Process source net of clb
				if(ff != null)
				{
					TimingNode sourceNode = null;
					ArrayList<TimingNode> ffNodeList = blockMap.get(ff);
					for(int i = 0; i < ffNodeList.size(); i++)
					{
						if(ffNodeList.get(i).getType() == TimingNodeType.START_NODE)
						{
							sourceNode = ffNodeList.get(i);
							break;
						}
					}
					for(TimingEdge connectedEdge: sourceNode.getOutputs())
					{
						Block owner = connectedEdge.getOutput().getPin().owner;
						if(owner != lut)
						{
							int mhd = Math.abs(owner.getSite().x - newX) 
									+ Math.abs(owner.getSite().y - newY);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
				}
				else //only a lut in the clb, no ff
				{
					TimingNode sourceNode = null;
					ArrayList<TimingNode> lutNodeList = blockMap.get(lut);
					if(lutNodeList != null)
					{
						for(int i = 0; i < lutNodeList.size(); i++)
						{
							//WATCH OUT: start node in the case of a source LUT (= a LUT that only sources a net but doesn't sink any nets)
							if(lutNodeList.get(i).getType() == TimingNodeType.INTERNAL_SOURCE_NODE ||
									lutNodeList.get(i).getType() == TimingNodeType.START_NODE)
							{
								sourceNode = lutNodeList.get(i);
							}
						}

						for(TimingEdge connectedEdge: sourceNode.getOutputs())
						{
							Block owner = connectedEdge.getOutput().getPin().owner;
							//No need to check for ff as this is null
							int mhd = Math.abs(owner.getSite().x - newX)
									+ Math.abs(owner.getSite().y - newY);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
				}
				//Process sink nets of clb
				if(lut != null)
				{
					ArrayList<TimingNode> lutNodeList = blockMap.get(lut);
					if(lutNodeList != null)
					{
						for(int i = 0; i < lutNodeList.size(); i++)
						{
							TimingNode node = lutNodeList.get(i);
							if(node.getType() == TimingNodeType.INTERNAL_SINK_NODE)
							{
								if(node.getInputs().size() > 1)
								{
									System.err.println("There should only be one input to an internalSinkNode!");
								}
								TimingEdge connectedEdge = node.getInputs().get(0);
								Block owner = connectedEdge.getInput().getPin().owner;
								if(owner != ff)
								{
									int mhd = Math.abs(owner.getSite().x - newX)
											+ Math.abs(owner.getSite().y - newY);
									deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
									affectedEdgeList.add(connectedEdge);
								}
							}
						}
					}
				}
				else //only a ff in the clb, no lut
				{
					ArrayList<TimingNode> ffNodeList = blockMap.get(ff);
					for(int i = 0; i < ffNodeList.size(); i++)
					{
						TimingNode node = ffNodeList.get(i);
						if(node.getType() == TimingNodeType.END_NODE)
						{
							if(node.getInputs().size() > 1)
							{
								System.err.println("There should only be one input to an endNode!");
							}
							TimingEdge connectedEdge = node.getInputs().get(0);
							Block owner = connectedEdge.getInput().getPin().owner;
							//No need to check for lut as this is null
							int mhd = Math.abs(owner.getSite().x - newX)
									+ Math.abs(owner.getSite().y - newY);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
				}
			}
			else if(swap.pl1.type == SiteType.HARDBLOCK)
			{
				HardBlock hb;
				int newX;
				int newY;
				if(swap.pl1.block != null)
				{
					hb = (HardBlock)swap.pl1.block;
					newX = swap.pl2.x;
					newY = swap.pl2.y;
				}
				else
				{
					hb = (HardBlock)swap.pl2.block;
					newX = swap.pl1.x;
					newY = swap.pl1.y;
				}
				
				//Process hardBlock (inputs and outputs)
				ArrayList<TimingNode> hbNodeList = blockMap.get(hb);
				for(TimingNode node: hbNodeList)
				{
					if(node.getType() == TimingNodeType.INTERNAL_SOURCE_NODE || 
									node.getType() == TimingNodeType.START_NODE) //TimingNode represents an output of the hardBlock
					{
						for(TimingEdge connectedEdge: node.getOutputs())
						{
							Block owner = connectedEdge.getOutput().getPin().owner;
							int mhd = Math.abs(owner.getSite().x - newX)
									+ Math.abs(owner.getSite().y - newY);
							deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
							affectedEdgeList.add(connectedEdge);
						}
					}
					else //TimingNode represents an input of the hardBlock
					{
						if(node.getInputs().size() > 1)
						{
							System.err.println("There should only be one input to an endNode or an internalSinkNode!");
						}
						TimingEdge connectedEdge = node.getInputs().get(0);
						Block owner = connectedEdge.getInput().getPin().owner;
						int mhd = Math.abs(owner.getSite().x - newX)
								+ Math.abs(owner.getSite().y - newY);
						deltaCost += connectedEdge.calculateDeltaCost(MHD_DELAY * mhd);
						affectedEdgeList.add(connectedEdge);
					}
				}
			}
		}
		return deltaCost;
	}
	
	public void pushThrough()
	{
		for(TimingEdge edge: affectedEdgeList)
		{
			edge.pushThrough();
		}
		affectedEdgeList.clear();
	}
	
	public void revert()
	{
		for(TimingEdge edge: affectedEdgeList)
		{
			edge.revert();
		}
		affectedEdgeList.clear();
	}
	
	public double calculateTotalCost()
	{
		double totalCost = 0.0;
		for(TimingEdge edge: edges)
		{
			totalCost += edge.getCost();
		}
		return totalCost;
	}
	
	public void recalculateAllSlacksCriticalities()
	{
		calculateArrivalTimesFromScratch();
		maxDelay = calculateMaximalDelay();
		calculateRequiredTimesFromScratch();
		for(TimingEdge edge: edges)
		{
			edge.recalculateSlackCriticality(maxDelay, criticalityExponent);
		}
	}
	
	public void setCriticalityExponent(double criticalityExponent)
	{
		this.criticalityExponent = criticalityExponent;
	}
	
	public void mapTopLevelPinsToTimingGraph(PackedCircuit packedCircuit)
	{
		clbsMapped = true;
		clbPinMap = new HashMap<>();
		for(Net net: packedCircuit.getNets().values())
		{
			//Process net source
			TimingNode sourceNode = null;
			if(net.source.owner.type == BlockType.INPUT)
			{
				sourceNode = blockMap.get(net.source.owner).get(0);
			}
			else
			{
				if(net.source.owner.type == BlockType.CLB) //Owner of net source is a CLB
				{
					Ble ble = ((Clb)net.source.owner).getBle();
					if(ble.isFFUsed())
					{
						ArrayList<TimingNode> nodeList = blockMap.get(ble.getFlipflop());
						for(TimingNode node: nodeList)
						{
							if(node.getType() == TimingNodeType.START_NODE)
							{
								sourceNode = node;
								break;
							}
						}
					}
					else
					{
						ArrayList<TimingNode> nodeList = blockMap.get(ble.getLut());
						if(nodeList == null) //Only occurs for constant generator CLBs
						{
							continue;
						}
						for(TimingNode node: nodeList)
						{
							if(node.getType() == TimingNodeType.INTERNAL_SOURCE_NODE || node.getType() == TimingNodeType.START_NODE)
							{
								sourceNode = node;
								break;
							}
						}
					}
				}
				else //Owner of net source is a HardBlock
				{
					ArrayList<TimingNode> nodeList = blockMap.get(net.source.owner);
					for(TimingNode node: nodeList)
					{
						if(node.getPin() == net.source)
						{
							sourceNode = node;
							break;
						}
					}
				}
			}
			clbPinMap.put(net.source, sourceNode);
			
			//Process net sinks
			for(Pin sinkPin: net.sinks)
			{
				TimingNode sinkNode = null;
				if(sinkPin.owner.type == BlockType.OUTPUT)
				{
					sinkNode = blockMap.get(sinkPin.owner).get(0);
				}
				else
				{
					if(sinkPin.owner.type == BlockType.CLB) //Owner of the net sink is a CLB
					{
						Ble ble = ((Clb)sinkPin.owner).getBle();
						if(ble.getLut() == null) //Clb only contains a flipflop
						{
							ArrayList<TimingNode> ffNodes = blockMap.get(ble.getFlipflop());
							for(TimingNode node: ffNodes)
							{
								if(node.getType() == TimingNodeType.END_NODE)
								{
									sinkNode = node;
									break;
								}
							}
						}
						else //Clb contains a LUT
						{
							//To know if we have the correct timingNode we need to know the index of the sinkPin in the CLB
							int sinkPinIndex = -1;
							Pin[] clbInputs = ((Clb)sinkPin.owner).input;
							for(int index = 0; index < clbInputs.length; index++)
							{
								if(clbInputs[index] == sinkPin)
								{
									sinkPinIndex = index;
									break;
								}
							}
							ArrayList<TimingNode> lutNodes = blockMap.get(ble.getLut());
							for(TimingNode node: lutNodes)
							{
								//To know if we have the correct timingNode we need to know the index of the pin represented by the timingNode in the LUT
								int timingNodePinIndex = -1;
								Pin[] lutInputs = ble.getLut().getInputs();
								for(int index = 0; index < lutInputs.length; index++)
								{
									if(lutInputs[index] == node.getPin())
									{
										timingNodePinIndex = index;
										break;
									}
								}
								if(sinkPinIndex == timingNodePinIndex)
								{
									sinkNode = node;
									break;
								}
							}
						}
					}
					else //Owner of the net sink is a HardBlock
					{
						ArrayList<TimingNode> hbNodes = blockMap.get(sinkPin.owner);
						for(TimingNode node: hbNodes)
						{
							if(node.getPin() == sinkPin)
							{
								sinkNode = node;
								break;
							}
						}
					}
				}
				clbPinMap.put(sinkPin, sinkNode);
			}
		}
	}
	
	public void mapNetsToEdges(PackedCircuit packedCircuit)
	{
		edgeMap = new HashMap<>();
		for(TimingEdge edge: edges)
		{
			Block inputOwner = edge.getInput().getPin().owner; //TimingEdge input = net source
			Block outputOwner = edge.getOutput().getPin().owner; //TimingEdge output = net sink
			Net net = null;
			if(inputOwner.type == BlockType.FLIPFLOP || inputOwner.type == BlockType.INPUT)
			{
				net = packedCircuit.nets.get(inputOwner.name);
			}
			else
			{
				if(inputOwner.type == BlockType.LUT)
				{
					if(inputOwner != outputOwner)
					{
						net = packedCircuit.nets.get(inputOwner.name); //Will be null if the net is internal to clb (lut -> ff)
					}
				}
				else //InputOwner is a HardBlock
				{
					if(inputOwner != outputOwner)
					{
						HardBlock inputOwnerHb = (HardBlock)inputOwner;
						Pin sourcePin = edge.getInput().getPin();
						for(int index = 0; index < inputOwnerHb.getOutputs().length; index++)
						{
							Pin pin = inputOwnerHb.getOutputs()[index];
							if(pin == sourcePin)
							{
								net = packedCircuit.nets.get(inputOwnerHb.getOutputNetName(index));
								break;
							}
						}
						
					}
				}
			}
			if(net != null)
			{
				if(edgeMap.get(net) == null)
				{
					edgeMap.put(net, new ArrayList<TimingEdge>());
				}
				edgeMap.get(net).add(edge);
			}
		}
	}
	
	public ArrayList<TimingEdge> getNetEdges(Net net)
	{
		return edgeMap.get(net);
	}
	
	public ArrayList<TimingNode> getBlockNodes(Block block)
	{
		return blockMap.get(block);
	}
	
	public double getConnectionCriticalityWithExponent(Pin sourceTopLevelPin, Pin sinkTopLevelPin)
	{
		double criticalityWithExponent = -1.0;
		if(clbsMapped)
		{
			TimingNode sourceNode = clbPinMap.get(sourceTopLevelPin);
			TimingNode sinkNode = clbPinMap.get(sinkTopLevelPin);
			if(sourceNode == null) //This occurs for constant generators
			{
//				System.out.println(sourceTopLevelPin.name + " is not in the clbPinMap");
				return Math.pow(0.2, criticalityExponent);
			}
			//System.out.print("source = " + sourceNode.getPin().name + ", sink = " + sinkNode.getPin().name + " ");
			for(TimingEdge edge: sourceNode.getOutputs())
			{
				if(edge.getOutput() == sinkNode)
				{
					criticalityWithExponent = edge.getCriticalityWithExponent();
					break;
				}
			}
			
			if(criticalityWithExponent < 0.0)
			{
				System.err.println("Trouble: criticality not found!");
			}
		}
		else
		{
			System.err.println("Trouble: clbMap not yet initialized!");
		}
		return criticalityWithExponent;
	}
	
	public void updateDelays()
	{
		for(TimingEdge edge: edges)
		{
			TimingNode edgeInput = edge.getInput();
			Block edgeInputOwner = edgeInput.getPin().owner;
			Block edgeOutputOwner = edge.getOutput().getPin().owner;
			if(edgeInput.getType() == TimingNodeType.START_NODE || edgeInput.getType() == TimingNodeType.INTERNAL_SOURCE_NODE)
			{
				int mhd = Math.abs(edgeInputOwner.getSite().x - edgeOutputOwner.getSite().x)
						+ Math.abs(edgeInputOwner.getSite().y - edgeOutputOwner.getSite().y);
				edge.setDelay(MHD_DELAY * mhd);
			}
		}
		recalculateAllSlacksCriticalities();
	}
	
	private void processStartPin(Pin startPin)
	{
		Map<String,Net> nets = circuit.getNets();
		Stack<Net> netsStack = new Stack<>();
		Stack<Integer> sinkIndexStack = new Stack<>();
		Stack<TimingNode> currentTimingNodeStack = new Stack<>();
		TimingNode startNode = new TimingNode(TimingNodeType.START_NODE, startPin);
		Block startBlock = startPin.owner;
		startNodes.add(startNode);
		if(blockMap.get(startBlock) == null)
		{
			blockMap.put(startBlock, new ArrayList<TimingNode>());
		}
		blockMap.get(startBlock).add(startNode);
		Net currentNet;
		if(startBlock.type != BlockType.HARDBLOCK_CLOCKED)
		{
			currentNet = nets.get(startBlock.name);
		}
		else
		{
			Pin[] hardBlockPins = ((HardBlock)startBlock).getOutputs();
			int index = 0;
			for(Pin hbPin: hardBlockPins)
			{
				if(hbPin == startPin)
				{
					break;
				}
				index++;
			}
			currentNet = nets.get(((HardBlock)startBlock).getOutputNetName(index));
		}
		int currentIndex = 0;
		TimingNode currentNode = startNode;
		if(currentNet == null || currentNet.sinks.size() == 0) //Can happen with clock nets which are declared as an input in the blif file
		{
			return;
		}
		boolean keepGoing = true;
		while(keepGoing)
		{
			Pin currentSink = currentNet.sinks.get(currentIndex);
			int mhd = Math.abs(currentNode.getPin().owner.getSite().x - currentSink.owner.getSite().x) 
					+ Math.abs(currentNode.getPin().owner.getSite().y - currentSink.owner.getSite().y);
			if(currentSink.owner.type == BlockType.FLIPFLOP || currentSink.owner.type == BlockType.OUTPUT || 
																		currentSink.owner.type == BlockType.HARDBLOCK_CLOCKED)
			{
				TimingNode endNode = new TimingNode(TimingNodeType.END_NODE, currentSink);
				TimingEdge connection = new TimingEdge(currentNode, endNode, mhd * MHD_DELAY);
				edges.add(connection);
				currentNode.addOutput(connection);
				endNode.addInput(connection);
				endNodes.add(endNode);
				if(blockMap.get(currentSink.owner) == null)
				{
					blockMap.put(currentSink.owner, new ArrayList<TimingNode>());
				}
				blockMap.get(currentSink.owner).add(endNode);
			}
			else
			{
				if(currentSink.owner.type == BlockType.LUT) //Is a LUT ==> keep on going
				{
					//Process TimingNode for Lut input
					TimingNode inputNode = new TimingNode(TimingNodeType.INTERNAL_SINK_NODE, currentSink);
					TimingEdge connectionOne = new TimingEdge(currentNode, inputNode, mhd * MHD_DELAY);
					edges.add(connectionOne);
					currentNode.addOutput(connectionOne);
					inputNode.addInput(connectionOne);
					if(blockMap.get(currentSink.owner) == null)
					{
						blockMap.put(currentSink.owner, new ArrayList<TimingNode>());
					}
					List<TimingNode> lutNodeList = blockMap.get(currentSink.owner);
					lutNodeList.add(inputNode);
					//Process TimingNode for LUT output
					TimingNode outputNode = null;
					Pin lutOutput = ((Lut)currentSink.owner).getOutputs()[0];
					for(TimingNode localNode: lutNodeList)
					{
						if(localNode.getPin() == lutOutput)
						{
							outputNode = localNode;
							break;
						}
					}
					if(outputNode == null)
					{
						outputNode = new TimingNode(TimingNodeType.INTERNAL_SOURCE_NODE, lutOutput);
						lutNodeList.add(outputNode);
						TimingEdge connectionTwo = new TimingEdge(inputNode, outputNode, LUT_DELAY);
						edges.add(connectionTwo);
						inputNode.addOutput(connectionTwo);
						outputNode.addInput(connectionTwo);
						netsStack.push(currentNet);
						sinkIndexStack.push(currentIndex);
						currentTimingNodeStack.push(currentNode);
						currentNet = nets.get(currentSink.owner.name); //Output net of the LUT
						currentIndex = -1; //Will immediately be increased (see below)
						currentNode = outputNode;
					}
					else
					{
						TimingEdge connectionTwo = new TimingEdge(inputNode, outputNode, LUT_DELAY);
						edges.add(connectionTwo);
						inputNode.addOutput(connectionTwo);
						outputNode.addInput(connectionTwo);
					}
				}
				else //Must be an unclocked hardblock ==> keep on going
				{
					TimingNode inputNode = new TimingNode(TimingNodeType.INTERNAL_SINK_NODE, currentSink);
					TimingEdge connectionOne = new TimingEdge(currentNode, inputNode, mhd * MHD_DELAY);
					edges.add(connectionOne);
					currentNode.addOutput(connectionOne);
					inputNode.addInput(connectionOne);
					if(blockMap.get(currentSink.owner) == null)
					{
						blockMap.put(currentSink.owner, new ArrayList<TimingNode>());
					}
					List<TimingNode> hbNodeList = blockMap.get(currentSink.owner);
					hbNodeList.add(inputNode);
					boolean firstTime = true;
					Pin[] outputs = ((HardBlock)currentSink.owner).getOutputs();
					for(int i = 0; i < outputs.length; i++) //Loop over every output of the hardblock
					{
						Pin hbOutput = outputs[i];
						TimingNode outputNode = null;
						for(TimingNode localNode: hbNodeList)
						{
							if(localNode.getPin() == hbOutput)
							{
								outputNode = localNode;
								break;
							}
						}
						if(outputNode == null) //This is the first time we enter the hardblock
						{
							outputNode = new TimingNode(TimingNodeType.INTERNAL_SOURCE_NODE, hbOutput);
							hbNodeList.add(outputNode);
							TimingEdge connectionTwo = new TimingEdge(inputNode, outputNode, LUT_DELAY);
							edges.add(connectionTwo);
							inputNode.addOutput(connectionTwo);
							outputNode.addInput(connectionTwo);
							if(firstTime)
							{
								firstTime = false;
								netsStack.push(currentNet);
								sinkIndexStack.push(currentIndex);
								currentTimingNodeStack.push(currentNode);

							}
							netsStack.push(nets.get(((HardBlock)currentSink.owner).getOutputNetName(i)));
							sinkIndexStack.push(-1); //This will be immediately increased (see below)
							currentTimingNodeStack.push(outputNode);
							if(i == outputs.length - 1)
							{
								currentNet = netsStack.pop();
								currentIndex = sinkIndexStack.pop();
								currentNode = currentTimingNodeStack.pop();
							}
						}
						else
						{
							TimingEdge connectionTwo = new TimingEdge(inputNode, outputNode, LUT_DELAY);
							edges.add(connectionTwo);
							inputNode.addOutput(connectionTwo);
							outputNode.addInput(connectionTwo);
						}
					}
				}
			}
			++currentIndex;			
			
			
			
			if(currentNet == null)
			{
				System.out.println("This is the problem: startpin = " + startPin.owner.name + 
									", current sink owner = " + currentSink.owner.name);
			}
			
			
			
			if(!(currentIndex < currentNet.sinks.size()))
			{
				while(!(currentIndex < currentNet.sinks.size()) && keepGoing)
				{
					if(netsStack.isEmpty())
					{
						keepGoing = false;
					}
					else
					{
						currentNet = netsStack.pop();
						currentIndex = sinkIndexStack.pop();
						currentNode = currentTimingNodeStack.pop();
						++currentIndex;
					}
				}
			}
		}
	}
	
	private void calculateArrivalTimesFromScratch()
	{
		//Clear all arrival times
		for(TimingEdge edge: edges)
		{
			edge.getInput().setTArrival(0.0);
			edge.getOutput().setTArrival(0.0);
		}
		
		//Do a breadth first search of the timing graph (every startNode separately)
		for(TimingNode startNode: startNodes)
		{
			startNode.setTArrival(0.0);
			Queue<TimingNode> nodeQueue = new LinkedList<>();
			nodeQueue.add(startNode);
			
			while(!nodeQueue.isEmpty())
			{
				TimingNode currentNode = nodeQueue.remove();
				for(TimingEdge edge: currentNode.getOutputs())
				{
					TimingNode connectedNode = edge.getOutput();
					double possibleNewTArrival = currentNode.getTArrival() + edge.getDelay();
					if(possibleNewTArrival > connectedNode.getTArrival())
					{
						connectedNode.setTArrival(possibleNewTArrival);
						nodeQueue.add(connectedNode);
					}
				}
			}
		}
	}
	
	public Block[] getCriticalPath()
	{
		TimingNode[] criticalPath = null;
		double curMaxDelay = 0.0;
		double curDelay = 0.0;
		Stack<TimingNode> nodeStack = new Stack<>();
		Stack<Integer> indexStack = new Stack<>();
		Stack<Double> curDelayStack = new Stack<>();
		
		for(TimingNode startNode: startNodes)
		{
			TimingNode currentNode = startNode;
			int currentIndex = 0;
			
			boolean firstTime = true;
			if(startNode.getOutputs().size() == 0)
			{
				continue;
			}
			while(!nodeStack.isEmpty() || firstTime)
			{
				firstTime = false;
				if(currentNode.getOutputs().size() == 0 && !nodeStack.isEmpty())
				{
					while(currentIndex >= currentNode.getOutputs().size() && !nodeStack.isEmpty())
					{
						currentNode = nodeStack.pop();
						curDelay = curDelayStack.pop();
						currentIndex = indexStack.pop();
						currentIndex++;
					}
					if(nodeStack.isEmpty())
					{
						break;
					}
				}
				TimingEdge edge = currentNode.getOutputs().get(currentIndex);
				if(edge.getOutput().getType() == TimingNodeType.END_NODE) //We reached the end
				{
					curDelay += edge.getDelay();
					if(curDelay > curMaxDelay)
					{
						curMaxDelay = curDelay;
						nodeStack.push(currentNode);
						nodeStack.push(edge.getOutput());
						criticalPath = new TimingNode[nodeStack.size()];
						nodeStack.toArray(criticalPath);
						nodeStack.pop();
						nodeStack.pop();
					}
					curDelay -= edge.getDelay();
					currentIndex++;
					while(currentIndex >= currentNode.getOutputs().size() && !nodeStack.isEmpty())
					{
						currentNode = nodeStack.pop();
						curDelay = curDelayStack.pop();
						currentIndex = indexStack.pop();
						currentIndex++;
					}
				}
				else //We have to dig deeper
				{
					nodeStack.push(currentNode);
					indexStack.push(currentIndex);
					curDelayStack.push(curDelay);
					curDelay += edge.getDelay();
					currentNode = edge.getOutput();
					currentIndex = 0;
				}
			}
			
		}
		
		ArrayList<Block> criticalPathBlocks = new ArrayList<>(); 
		System.out.println("Max delay = " + curMaxDelay);
		System.out.print("Critical path: ");
		for(int i = 0; i < criticalPath.length; i++)
		{
			System.out.print(criticalPath[i].getPin().name);
			if(i != criticalPath.length - 1)
			{
				System.out.print(" -- ");
				System.out.print(criticalPath[i+1].getInputs().get(0).getDelay());
				System.out.print("-->");
			}
			if(criticalPathBlocks.size() > 0 && criticalPathBlocks.get(criticalPathBlocks.size() - 1) != criticalPath[i].getPin().owner)
			{
				criticalPathBlocks.add(criticalPath[i].getPin().owner);
			}
			else
			{
				if(criticalPathBlocks.size() == 0)
				{
					criticalPathBlocks.add(criticalPath[i].getPin().owner);
				}
			}
		}
		System.out.println();
		
		for(Block block: criticalPathBlocks)
		{
			System.out.print(block.name + " --> ");
		}
		
		Block[] toReturn = new Block[criticalPathBlocks.size()];
		criticalPathBlocks.toArray(toReturn);
		return toReturn;
	}
	
	private void calculateRequiredTimesFromScratch()
	{
		//Clear all required times
		for(TimingEdge edge: edges)
		{
			edge.getInput().setTRequired(Double.MAX_VALUE);
			edge.getOutput().setTRequired(Double.MAX_VALUE);
		}
		
		//Do a breadth first search of the timing graph (every endNode separately)
		for(TimingNode endNode: endNodes)
		{
			endNode.setTRequired(maxDelay);
			Queue<TimingNode> nodeQueue = new LinkedList<>();
			nodeQueue.add(endNode);
			
			while(!nodeQueue.isEmpty())
			{
				TimingNode currentNode = nodeQueue.remove();
				for(TimingEdge edge: currentNode.getInputs())
				{
					TimingNode connectedNode = edge.getInput();
					double possibleNewTRequired = currentNode.getTRequired() - edge.getDelay();
					if(possibleNewTRequired < connectedNode.getTRequired())
					{
						connectedNode.setTRequired(possibleNewTRequired);
						nodeQueue.add(connectedNode);
					}
				}
			}
		}
	}
	
	public String getBlockNodesString(String blockName)
	{
		String toReturn = "";
		for(Block block:blockMap.keySet())
		{
			if(block.name.contains(blockName))
			{
				ArrayList<TimingNode> nodeList = blockMap.get(block);
				for(TimingNode node: nodeList)
				{
					toReturn += node.getPin().name + "(" + node.getTRequired() + ") --" + node.getOutputs().get(0).getDelay() + 
							"--> " + node.getOutputs().get(0).getOutput().getPin().name + "(" + 
							node.getOutputs().get(0).getOutput().getTRequired() + ")\n";
				}
			}
		}
		return toReturn;
	}
	
	public void checkBlockMap()
	{
		for(Lut lut: circuit.getLuts().values())
		{
			if(blockMap.get(lut) == null)
			{
				System.out.println("Lut " + lut.name + " is not present in the block map");
			}
		}
		for(Flipflop ff: circuit.getFlipflops().values())
		{
			if(blockMap.get(ff) == null)
			{
				System.out.println("FF " + ff.name + " is not present in the block map");
			}
		}
	}
	
	@Override
	public String toString()
	{
		String toReturn = "";
		for(TimingNode startNode: startNodes)
		{
			toReturn += (startNode.getPin().name);
			List<TimingEdge> connectedEdges = startNode.getOutputs();
			while(!connectedEdges.isEmpty())
			{
				TimingEdge firstEdge = connectedEdges.get(0);
				toReturn += "--" + firstEdge.getDelay() + "-->" + firstEdge.getOutput().getPin().name;
				connectedEdges = firstEdge.getOutput().getOutputs();
			}
			toReturn += "\n";
		}
		return toReturn;
	}
	
	private ArrayList<ArrayList<TimingEdge>> edgeList;
	
	public void mapAnalyticalPlacerIndicesToEdges(Map<Block,Integer> indexMap, PackedCircuit circuit)
	{
		edgeList = new ArrayList<ArrayList<TimingEdge>>();
		
		for(int i = 0; i < indexMap.size(); i++)
		{
			edgeList.add(null);
		}
		
		for(Clb clb: circuit.clbs.values())
		{
			int index = indexMap.get(clb);
			Lut lut = clb.getBle().getLut();
			Flipflop ff = clb.getBle().getFlipflop();
			
			if(lut == null) //only a flipflop in the game
			{
				ArrayList<TimingEdge> edges = new ArrayList<>();
				ArrayList<TimingNode> nodeList = blockMap.get(ff);
				for(TimingNode node: nodeList)
				{
					if(node.getType() == TimingNodeType.START_NODE)
					{
						edges.addAll(node.getOutputs());
					}
					else //Must be an end node
					{
						edges.addAll(node.getInputs());
					}
				}
				edgeList.set(index, edges);
			}
			else
			{
				if(ff == null) //only a lut in the game
				{
					ArrayList<TimingEdge> edges = new ArrayList<>();
					ArrayList<TimingNode> nodeList = blockMap.get(lut);
					if(nodeList != null)
					{
						for(TimingNode node: nodeList)
						{
							if(node.getType() == TimingNodeType.INTERNAL_SOURCE_NODE || node.getType() == TimingNodeType.START_NODE)
							{
								edges.addAll(node.getOutputs());
							}
							else //Must be an internal sink node or end node
							{
								edges.addAll(node.getInputs());
							}
						}
					}
					edgeList.set(index, edges);
				}
				else //both are in the game
				{
					ArrayList<TimingEdge> edges = new ArrayList<>();
					
					ArrayList<TimingNode> lutNodeList = blockMap.get(lut);
					if(lutNodeList != null)
					{
						for(TimingNode node: lutNodeList)
						{
							if(node.getType() == TimingNodeType.INTERNAL_SINK_NODE || node.getType() == TimingNodeType.END_NODE)
							{
								edges.addAll(node.getInputs());
							}
						}
					}
					ArrayList<TimingNode> ffNodeList = blockMap.get(ff);
					for(TimingNode node: ffNodeList)
					{
						if(node.getType() == TimingNodeType.INTERNAL_SOURCE_NODE || node.getType() == TimingNodeType.START_NODE)
						{
							edges.addAll(node.getOutputs());
						}
					}
					
					edgeList.set(index, edges);
				}
			}
		}
		
		for(Vector<HardBlock> hbVector: circuit.getHardBlocks())
		{
			for(HardBlock hb: hbVector)
			{
				int index = indexMap.get(hb);
				ArrayList<TimingEdge> edges = new ArrayList<>();
				ArrayList<TimingNode> hbNodeList = blockMap.get(hb);
				for(TimingNode node: hbNodeList)
				{
					if(node.getType() == TimingNodeType.INTERNAL_SOURCE_NODE || node.getType() == TimingNodeType.START_NODE)
					{
						edges.addAll(node.getOutputs());
					}
					else //Must be an internal sink node or end node
					{
						edges.addAll(node.getInputs());
					}
				}
				edgeList.set(index, edges);
			}
		}
		
	}
	
	public ArrayList<TimingEdge> getIndexEdges(int index)
	{
		return edgeList.get(index);
	}
	
}