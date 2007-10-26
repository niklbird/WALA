/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.ipa.slicer;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.util.CompoundIterator;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Collection;
import com.ibm.wala.util.collections.IteratorUtil;
import com.ibm.wala.util.debug.*;
import com.ibm.wala.util.graph.AbstractNumberedGraph;
import com.ibm.wala.util.graph.EdgeManager;
import com.ibm.wala.util.graph.NodeManager;
import com.ibm.wala.util.graph.NumberedEdgeManager;
import com.ibm.wala.util.graph.impl.SlowNumberedNodeManager;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;
import com.ibm.wala.util.intset.OrdinalSet;

/**
 * System dependence graph.
 * 
 * An SDG comprises a set of PDGs, one for each method. We compute these lazily.
 * 
 * Prototype implementation. Not efficient.
 * 
 * @author sjfink
 * 
 */
public class SDG extends AbstractNumberedGraph<Statement> implements ISDG {

  /**
   * node manager for graph API
   */
  private final Nodes nodeMgr = new Nodes();

  /**
   * edge manager for graph API
   */
  private final Edges edgeMgr = new Edges();

  /**
   * governing call graph
   */
  private final CallGraph cg;

  /**
   * governing pointer analysis
   */
  private final PointerAnalysis pa;

  /**
   * keeps track of PDG for each call graph node
   */
  private final Map<CGNode, PDG> pdgMap = HashMapFactory.make();

  /**
   * governs data dependence edges in the graph
   */
  private final DataDependenceOptions dOptions;

  /**
   * governs control dependence edges in the graph
   */
  private final ControlDependenceOptions cOptions;

  /**
   * the set of heap locations which may be written (transitively) by each node.
   * These are logically return values in the SDG.
   */
  private final Map<CGNode, OrdinalSet<PointerKey>> mod;

  /**
   * the set of heap locations which may be read (transitively) by each node.
   * These are logically parameters in the SDG.
   */
  private final Map<CGNode, OrdinalSet<PointerKey>> ref;
  
  /**
   * If non-null, represents the heap locations to exlude from data dependence
   */
  private final HeapExclusions heapExclude;

  private final ModRef modRef;

  public SDG(final CallGraph cg, PointerAnalysis pa, DataDependenceOptions dOptions, ControlDependenceOptions cOptions) {
    this(cg, pa, ModRef.make(), dOptions, cOptions, null);
  }

  public SDG(final CallGraph cg, PointerAnalysis pa, ModRef modRef, DataDependenceOptions dOptions, ControlDependenceOptions cOptions) {
    this(cg, pa, modRef, dOptions, cOptions, null);
  }

  public SDG(CallGraph cg, PointerAnalysis pa, ModRef modRef, DataDependenceOptions dOptions, ControlDependenceOptions cOptions, HeapExclusions heapExclude) throws IllegalArgumentException {
    super();
    if (dOptions == null) {
      throw new IllegalArgumentException("dOptions must not be null");
    }
    this.modRef = modRef;
    this.cg = cg;
    this.pa = pa;
    this.mod = dOptions.isIgnoreHeap() ? null : modRef.computeMod(cg, pa, heapExclude);
    this.ref = dOptions.isIgnoreHeap() ? null : modRef.computeRef(cg, pa, heapExclude);
    this.dOptions = dOptions;
    this.cOptions = cOptions;
    this.heapExclude = heapExclude;
  }

  @Override
  public String toString() {
    eagerConstruction();

    return super.toString();
  }

  /**
   * force eager construction of the entire SDG
   */
  private void eagerConstruction() {
    computeAllPDGs();
  }

  /**
   * force computation of all PDGs in the SDG
   */
  private void computeAllPDGs() {
    for (Iterator<? extends CGNode> it = cg.iterator(); it.hasNext();) {
      CGNode n = it.next();
      getPDG(n);
    }
  }

  /**
   * iterate over the nodes <bf>without</bf> constructing any new ones. Use
   * with extreme care. May break graph traversals that lazily add more nodes.
   */
  public Iterator<? extends Statement> iterateLazyNodes() {
    return nodeMgr.iterateLazyNodes();
  }

  private class Nodes extends SlowNumberedNodeManager<Statement> {

    @Override
    public Iterator<Statement> iterator() {
      eagerConstruction();
      return super.iterator();
    }

    /**
     * iterate over the nodes <bf>without</bf> constructing any new ones. Use
     * with extreme care. May break graph traversals that lazily add more nodes.
     */
    Iterator<? extends Statement> iterateLazyNodes() {
      return super.iterator();
    }

    @Override
    public int getNumberOfNodes() {
      eagerConstruction();
      return super.getNumberOfNodes();
    }

  }

  private class Edges implements NumberedEdgeManager<Statement> {
    public void addEdge(Statement src, Statement dst) {
      Assertions.UNREACHABLE();
    }

    public int getPredNodeCount(Statement N) {
      return IteratorUtil.count(getPredNodes(N));
    }

    public Iterator<? extends Statement> getPredNodes(Statement N) {
      if (Assertions.verifyAssertions && dOptions.isIgnoreExceptions()) {
        Assertions._assert(!N.getKind().equals(Kind.EXC_RET_CALLEE));
        Assertions._assert(!N.getKind().equals(Kind.EXC_RET_CALLER));
      }
      switch (N.getKind()) {
      case NORMAL:
      case PHI:
      case PI:
      case EXC_RET_CALLEE:
      case NORMAL_RET_CALLEE:
      case PARAM_CALLER:
      case HEAP_PARAM_CALLER:
      case HEAP_RET_CALLEE:
      case CATCH:
        return getPDG(N.getNode()).getPredNodes(N);
      case EXC_RET_CALLER: {
        ParamStatement.ExceptionalReturnCaller nrc = (ParamStatement.ExceptionalReturnCaller) N;
        SSAAbstractInvokeInstruction call = nrc.getCall();
        Collection<Statement> result = Iterator2Collection.toCollection(getPDG(N.getNode()).getPredNodes(N));
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (CGNode t : cg.getPossibleTargets(N.getNode(),call.getCallSite())) {
            Statement s = new ParamStatement.ExceptionalReturnCallee(t);
            addNode(s);
            result.add(s);
          }
        }
        return result.iterator();
      }
      case NORMAL_RET_CALLER: {
        ParamStatement.NormalReturnCaller nrc = (ParamStatement.NormalReturnCaller) N;
        SSAAbstractInvokeInstruction call = nrc.getCall();
        Collection<Statement> result = Iterator2Collection.toCollection(getPDG(N.getNode()).getPredNodes(N));
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (CGNode t : cg.getPossibleTargets(N.getNode(), call.getCallSite())) {
            Statement s = new ParamStatement.NormalReturnCallee(t);
            addNode(s);
            result.add(s);
          }
        }
        return result.iterator();
      }
      case HEAP_RET_CALLER: {
        HeapStatement.ReturnCaller r = (HeapStatement.ReturnCaller) N;
        SSAAbstractInvokeInstruction call = r.getCall();
        Collection<Statement> result = Iterator2Collection.toCollection(getPDG(N.getNode()).getPredNodes(N));
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (CGNode t : cg.getPossibleTargets(N.getNode(), call.getCallSite())) {
            if (mod.get(t).contains(r.getLocation())) {
              Statement s = new HeapStatement.ReturnCallee(t, r.getLocation());
              addNode(s);
              result.add(s);
            }
          }
        }
        return result.iterator();
      }
      case PARAM_CALLEE: {
        ParamStatement.ParamCallee pac = (ParamStatement.ParamCallee) N;
        int parameterIndex = pac.getValueNumber() - 1;
        Collection<Statement> result = HashSetFactory.make(5);
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (Iterator<? extends CGNode> it = cg.getPredNodes(N.getNode()); it.hasNext();) {
            CGNode caller = it.next();
            for (Iterator<CallSiteReference> it2 = cg.getPossibleSites(caller, N.getNode()); it2.hasNext();) {
              CallSiteReference site = it2.next();
              IR ir = caller.getIR();
              IntSet indices = ir.getCallInstructionIndices(site);
              for (IntIterator ii = indices.intIterator(); ii.hasNext();) {
                int i = ii.next();
                SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) ir.getInstructions()[i];
                int p = call.getUse(parameterIndex);
                Statement s = new ParamStatement.ParamCaller(caller, call, p);
                addNode(s);
                result.add(s);
              }
            }
          }
        }
        if (!cOptions.equals(ControlDependenceOptions.NONE)) {
          Statement s = new MethodEntryStatement(N.getNode());
          addNode(s);
          result.add(s);
        }
        return result.iterator();
      }
      case HEAP_PARAM_CALLEE: {
        HeapStatement.ParamCallee hpc = (HeapStatement.ParamCallee) N;
        Collection<Statement> result = HashSetFactory.make(5);
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (Iterator<? extends CGNode> it = cg.getPredNodes(N.getNode()); it.hasNext();) {
            CGNode caller = it.next();
            for (Iterator<CallSiteReference> it2 = cg.getPossibleSites(caller, N.getNode()); it2.hasNext();) {
              CallSiteReference site = it2.next();
              IR ir = caller.getIR();
              IntSet indices = ir.getCallInstructionIndices(site);
              for (IntIterator ii = indices.intIterator(); ii.hasNext();) {
                int i = ii.next();
                Statement s = new HeapStatement.ParamCaller(caller, i, hpc.getLocation());
                addNode(s);
                result.add(s);
              }
            }
          }
        }
        if (!cOptions.equals(ControlDependenceOptions.NONE)) {
          Statement s = new MethodEntryStatement(N.getNode());
          addNode(s);
          result.add(s);
        }
        return result.iterator();
      }
      case METHOD_ENTRY:
        Collection<Statement> result = HashSetFactory.make(5);
        if (!cOptions.equals(ControlDependenceOptions.NONE)) {
          for (Iterator<? extends CGNode> it = cg.getPredNodes(N.getNode()); it.hasNext();) {
            CGNode caller = it.next();
            for (Iterator<CallSiteReference> it2 = cg.getPossibleSites(caller, N.getNode()); it2.hasNext();) {
              CallSiteReference site = it2.next();
              IR ir = caller.getIR();
              IntSet indices = ir.getCallInstructionIndices(site);
              for (IntIterator ii = indices.intIterator(); ii.hasNext();) {
                int i = ii.next();
                Statement s = new NormalStatement(caller, i);
                addNode(s);
                result.add(s);
              }
            }
          }
        }
        return result.iterator();
      default:
        Assertions.UNREACHABLE(N.getKind().toString());
        return null;
      }
    }

    public int getSuccNodeCount(Statement N) {
      return IteratorUtil.count(getSuccNodes(N));
    }

    public Iterator<? extends Statement> getSuccNodes(Statement N) {
      switch (N.getKind()) {
      case NORMAL:
        if (cOptions.equals(ControlDependenceOptions.NONE)) {
          return getPDG(N.getNode()).getSuccNodes(N);
        } else {
          NormalStatement ns = (NormalStatement) N;
          if (ns.getInstruction() instanceof SSAAbstractInvokeInstruction) {
            HashSet<Statement> result = HashSetFactory.make();
            SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) ns.getInstruction();
            for (CGNode t : cg.getPossibleTargets(N.getNode(), call.getCallSite())) {
              Statement s = new MethodEntryStatement(t);
              addNode(s);
              result.add(s);
            }
            return new CompoundIterator<Statement>(result.iterator(), getPDG(N.getNode()).getSuccNodes(N));
          } else {
            return getPDG(N.getNode()).getSuccNodes(N);
          }
        }
      case PHI:
      case CATCH:
      case EXC_RET_CALLER:
      case NORMAL_RET_CALLER:
      case PARAM_CALLEE:
      case HEAP_PARAM_CALLEE:
      case HEAP_RET_CALLER:
      case METHOD_ENTRY:
        return getPDG(N.getNode()).getSuccNodes(N);
      case EXC_RET_CALLEE: {
        Collection<Statement> result = HashSetFactory.make(5);
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (Iterator<? extends CGNode> it = cg.getPredNodes(N.getNode()); it.hasNext();) {
            CGNode caller = it.next();
            for (Iterator<CallSiteReference> it2 = cg.getPossibleSites(caller, N.getNode()); it2.hasNext();) {
              CallSiteReference site = it2.next();
              IR ir = caller.getIR();
              IntSet indices = ir.getCallInstructionIndices(site);
              for (IntIterator ii = indices.intIterator(); ii.hasNext();) {
                int i = ii.next();
                SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) ir.getInstructions()[i];
                Statement s = new ParamStatement.ExceptionalReturnCaller(caller, call);
                addNode(s);
                result.add(s);
              }
            }
          }
        }
        return result.iterator();
      }
      case NORMAL_RET_CALLEE: {
        Collection<Statement> result = HashSetFactory.make(5);
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (Iterator<? extends CGNode> it = cg.getPredNodes(N.getNode()); it.hasNext();) {
            CGNode caller = it.next();
            for (Iterator<CallSiteReference> it2 = cg.getPossibleSites(caller, N.getNode()); it2.hasNext();) {
              CallSiteReference site = it2.next();
              IR ir = caller.getIR();
              IntSet indices = ir.getCallInstructionIndices(site);
              for (IntIterator ii = indices.intIterator(); ii.hasNext();) {
                int i = ii.next();
                SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) ir.getInstructions()[i];
                Statement s = new ParamStatement.NormalReturnCaller(caller, call);
                addNode(s);
                result.add(s);
              }
            }
          }
        }
        return result.iterator();
      }
      case HEAP_RET_CALLEE: {
        HeapStatement.ReturnCallee r = (HeapStatement.ReturnCallee) N;
        Collection<Statement> result = HashSetFactory.make(5);
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence predecessors
          for (Iterator<? extends CGNode> it = cg.getPredNodes(N.getNode()); it.hasNext();) {
            CGNode caller = it.next();
            for (Iterator<CallSiteReference> it2 = cg.getPossibleSites(caller, N.getNode()); it2.hasNext();) {
              CallSiteReference site = it2.next();
              IR ir = caller.getIR();
              IntSet indices = ir.getCallInstructionIndices(site);
              for (IntIterator ii = indices.intIterator(); ii.hasNext();) {
                int i = ii.next();
                Statement s = new HeapStatement.ReturnCaller(caller, i, r.getLocation());
                addNode(s);
                result.add(s);
              }
            }
          }
        }
        return result.iterator();
      }
      case PARAM_CALLER: {
        ParamStatement.ParamCaller pac = (ParamStatement.ParamCaller) N;
        SSAAbstractInvokeInstruction call = pac.getCall();
        Collection<Statement> result = HashSetFactory.make(5);
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence successors
          for (CGNode t : cg.getPossibleTargets(N.getNode(), call.getCallSite())) {
            for (int i = 0; i < t.getMethod().getNumberOfParameters(); i++) {
              if (call.getUse(i) == pac.getValueNumber()) {
                Statement s = new ParamStatement.ParamCallee(t, i + 1);
                addNode(s);
                result.add(s);
              }
            }
          }
        }
        return result.iterator();
      }
      case HEAP_PARAM_CALLER:
        HeapStatement.ParamCaller pc = (HeapStatement.ParamCaller) N;
        SSAAbstractInvokeInstruction call = pc.getCall();
        Collection<Statement> result = HashSetFactory.make(5);
        if (!dOptions.equals(DataDependenceOptions.NONE)) {
          // data dependence successors
          for (CGNode t : cg.getPossibleTargets(N.getNode(), call.getCallSite())) {
            if (ref.get(t).contains(pc.getLocation())) {
              Statement s = new HeapStatement.ParamCallee(t, pc.getLocation());
              addNode(s);
              result.add(s);
            }
          }
        }
        return result.iterator();
      default:
        Assertions.UNREACHABLE(N.getKind().toString());
        return null;
      }
    }

    public boolean hasEdge(Statement src, Statement dst) {
      switch (src.getKind()) {
      case NORMAL:
        if (cOptions.equals(ControlDependenceOptions.NONE)) {
          return getPDG(src.getNode()).hasEdge(src, dst);
        } else {
          NormalStatement ns = (NormalStatement) src;
          if (dst instanceof MethodEntryStatement) {
            if (ns.getInstruction() instanceof SSAAbstractInvokeInstruction) {
              SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) ns.getInstruction();
              return cg.getPossibleTargets(src.getNode(), call.getCallSite()).contains(dst.getNode());
            } else {
              return false;
            }
          } else {
            return getPDG(src.getNode()).hasEdge(src, dst);
          }
        }
      case PHI:
      case EXC_RET_CALLER:
      case NORMAL_RET_CALLER:
      case PARAM_CALLEE:
      case HEAP_PARAM_CALLEE:
      case HEAP_RET_CALLER:
      case METHOD_ENTRY:
        return getPDG(src.getNode()).hasEdge(src, dst);
      case EXC_RET_CALLEE: {
        if (dOptions.equals(DataDependenceOptions.NONE)) {
          return false;
        }
        if (dst.getKind().equals(Kind.EXC_RET_CALLER)) {
          ParamStatement.ExceptionalReturnCaller r = (ParamStatement.ExceptionalReturnCaller) dst;
          return cg.getPossibleTargets(r.getNode(), r.getCall().getCallSite()).contains(src.getNode());
        } else {
          return false;
        }
      }
      case NORMAL_RET_CALLEE: {
        if (dOptions.equals(DataDependenceOptions.NONE)) {
          return false;
        }
        if (dst.getKind().equals(Kind.NORMAL_RET_CALLER)) {
          ParamStatement.NormalReturnCaller r = (ParamStatement.NormalReturnCaller) dst;
          return cg.getPossibleTargets(r.getNode(), r.getCall().getCallSite()).contains(src.getNode());
        } else {
          return false;
        }
      }
      case HEAP_RET_CALLEE: {
        if (dOptions.equals(DataDependenceOptions.NONE)) {
          return false;
        }
        if (dst.getKind().equals(Kind.HEAP_RET_CALLER)) {
          HeapStatement.ReturnCaller r = (HeapStatement.ReturnCaller) dst;
          HeapStatement h = (HeapStatement) src;
          return h.getLocation().equals(r.getLocation())
              && cg.getPossibleTargets(r.getNode(), r.getCall().getCallSite()).contains(src.getNode());
        } else {
          return false;
        }
      }
      case PARAM_CALLER: {
        if (dOptions.equals(DataDependenceOptions.NONE)) {
          return false;
        }
        if (dst.getKind().equals(Kind.PARAM_CALLEE)) {
          ParamStatement.ParamCallee callee = (ParamStatement.ParamCallee) dst;
          ParamStatement.ParamCaller caller = (ParamStatement.ParamCaller) src;

          return caller.getValueNumber() == callee.getValueNumber()
              && cg.getPossibleTargets(caller.getNode(), caller.getCall().getCallSite()).contains(callee.getNode());
        } else {
          return false;
        }
      }
      case HEAP_PARAM_CALLER:
        if (dOptions.equals(DataDependenceOptions.NONE)) {
          return false;
        }
        if (dst.getKind().equals(Kind.HEAP_PARAM_CALLEE)) {
          HeapStatement.ParamCallee callee = (HeapStatement.ParamCallee) dst;
          HeapStatement.ParamCaller caller = (HeapStatement.ParamCaller) src;

          return caller.getLocation().equals(callee.getLocation())
              && cg.getPossibleTargets(caller.getNode(), caller.getCall().getCallSite()).contains(callee.getNode());
        } else {
          return false;
        }
      default:
        Assertions.UNREACHABLE();
        return false;
      }
    }

    public void removeAllIncidentEdges(Statement node) {
      Assertions.UNREACHABLE();

    }

    public void removeEdge(Statement src, Statement dst) {
      Assertions.UNREACHABLE();

    }

    public void removeIncomingEdges(Statement node) {
      Assertions.UNREACHABLE();

    }

    public void removeOutgoingEdges(Statement node) {
      Assertions.UNREACHABLE();

    }

    public IntSet getPredNodeNumbers(Statement node) {
      // TODO: optimize me.
      MutableSparseIntSet result = new MutableSparseIntSet();
      for (Iterator<? extends Statement> it = getPredNodes(node); it.hasNext();) {
        Statement s = it.next();
        result.add(getNumber(s));
      }
      return result;
    }

    public IntSet getSuccNodeNumbers(Statement node) {
      // TODO: optimize me.
      MutableSparseIntSet result = new MutableSparseIntSet();
      for (Iterator<? extends Statement> it = getSuccNodes(node); it.hasNext();) {
        Statement s = it.next();
        result.add(getNumber(s));
      }
      return result;
    }
  }

  @Override
  protected EdgeManager<Statement> getEdgeManager() {
    return edgeMgr;
  }

  @Override
  protected NodeManager<Statement> getNodeManager() {
    return nodeMgr;
  }

  public PDG getPDG(CGNode node) {
    PDG result = pdgMap.get(node);
    if (result == null) {
	result = new PDG(node, pa, mod, ref, dOptions, cOptions, heapExclude, cg, modRef);
      pdgMap.put(node, result);
      for (Iterator<? extends Statement> it = result.iterator(); it.hasNext();) {
        nodeMgr.addNode(it.next());
      }
    }
    return result;
  }

  public ControlDependenceOptions getCOptions() {
    return cOptions;
  }

  public CallGraph getCallGraph() {
    return cg;
  }

}
