package org.rsdeob.stdlib.collections.graph.flow;

import org.rsdeob.stdlib.cfg.edge.FlowEdge;
import org.rsdeob.stdlib.collections.SetMultimap;
import org.rsdeob.stdlib.collections.graph.FastGraph;
import org.rsdeob.stdlib.collections.graph.FastGraphEdge;
import org.rsdeob.stdlib.collections.graph.FastGraphVertex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TarjanDominanceComputor<N extends FastGraphVertex> {

	private static final Sorter<?> sorterImpl = Sorters.get("dfs");
	
	private final FlowGraph<N, ?> graph;
	private final List<N> preOrder;
	private final Map<N, Integer> semiIndices;
	private final Map<N, N> parents;
	private final Map<N, N> propagationMap;
	private final Map<N, N> ancestors;
	private final Map<N, N> idoms;
	private final SetMultimap<N, N> semiDoms;
	private final SetMultimap<N, N> domChildren;
	private final SetMultimap<N, N> frontiers;
	private final SetMultimap<N, N> iteratedFrontiers;
	
	public TarjanDominanceComputor(FlowGraph<N, ?> graph) {
		this.graph = graph;
		preOrder = new ArrayList<>();
		semiIndices = new HashMap<>();
		parents = new HashMap<>();
		propagationMap = new HashMap<>();
		ancestors = new HashMap<>();
		idoms = new HashMap<>();
		semiDoms = new SetMultimap<>();
		domChildren = new SetMultimap<>();
		frontiers = new SetMultimap<>();
		iteratedFrontiers = new SetMultimap<>();
		
		computePreOrder();
		computeDominators();
		touchTree();
		computeFrontiers();
		computeIteratedFrontiers();
	}
	
	public Set<N> children(N n) {
		return domChildren.get(n);
	}
	
	public Set<N> semiDoms(N n) {
		return semiDoms.get(n);
	}
	
	public Set<N> frontier(N n) {
		return frontiers.get(n);
	}
	
	public Set<N> iteratedFrontier(N n) {
		return iteratedFrontiers.get(n);
	}
	
	public N idom(N n) {
		return idoms.get(n);
	}
	
	@SuppressWarnings("unchecked")
	private Sorter<N> sorter() {
		return (Sorter<N>) sorterImpl;
	}
	
	private void touchTree() {
		for(N n : idoms.keySet()) {
			domChildren.put(idoms.get(n), n);
		}
	}
	
	private Iterator<N> topoSort() {
		LinkedList<N> list = new LinkedList<>();
		for(N n : preOrder) {
			int i = list.indexOf(idoms.get(n));
			if(i == -1) {
				list.add(n);
			} else {
				list.add(i + 1, n);
			}
		}
		return list.descendingIterator();
	}
	
	private void computeIteratedFrontiers() {
		for(N n : preOrder) {
			computeIteratedFrontiers(n);
		}
	}
	
	private void computeIteratedFrontiers(N n) {
		Set<N> res = new HashSet<>();
		
		Set<N> workingSet = new HashSet<>();
		workingSet.add(n);
		
		do {
			Set<N> newWorkingSet = new HashSet<>();
			Iterator<N> it = workingSet.iterator();
			while(it.hasNext()) {
				N n1 = it.next();
				
				Iterator<N> dfIt = frontier(n1).iterator();
				while(dfIt.hasNext()) {
					N n2 = dfIt.next();
					if(!res.contains(n2)) {
						newWorkingSet.add(n2);
						res.add(n2);
					}
				}
			}
			workingSet = newWorkingSet;
		} while(!workingSet.isEmpty());
		
		iteratedFrontiers.putAll(n, res);
	}
	
	private void computeFrontiers() {
		Iterator<N> it = topoSort();
		while(it.hasNext()) {
			N n = it.next();
			
			// DF(local)
			for(FlowEdge<N> e : graph.getEdges(n)) {
				// svar data isn't propagated across exception edges.
				// if(!(e instanceof TryCatchEdge)) {
					N succ = e.dst;
					if(idoms.get(succ) != n) {
						frontiers.put(n, succ);
					}
				// }
			}
			
			// DF(up)
			for(N forest : domChildren.get(n)) {
				for(N forestFrontier : frontiers.get(forest)) {
					if(idoms.get(forestFrontier) != n) {
						frontiers.put(n, forestFrontier);
					}
				}
			}
		}
	}
	
	private void computePreOrder() {
		Iterator<N> it = sorter().iterator(graph);
		while(it.hasNext()) {
			N n = it.next();
			if(!semiIndices.containsKey(n)) {
				preOrder.add(n);
				semiIndices.put(n, semiIndices.size());
				propagationMap.put(n, n);
				
				for(N succ : FastGraph.computeSuccessors(graph, n)) {
					if(!semiIndices.containsKey(succ)) {
						parents.put(succ, n);
					}
				}
			}
		}
	}
	
	private void computeDominators() {
		int size = semiIndices.size() - 1;
		
		// ignore entry
		
		// i>0 to i > 2
		for(int i=size; i > 0; i--) {
			N n = preOrder.get(i);
			N p = parents.get(n);
			
			int newIndex = semiIndices.get(n);
			for(FastGraphEdge<N> e : graph.getReverseEdges(n)) {
				newIndex = Math.min(newIndex, semiIndices.get(calcSemiDom(e.src)));
			}
			
			semiIndices.put(n, newIndex);
			
			N semiIndex = preOrder.get(newIndex);
			semiDoms.put(semiIndex, n);
			
			ancestors.put(n, p);
			
			for(N v : semiDoms.get(p)) {
				N u = calcSemiDom(v);
				
				if(semiIndices.get(u) < semiIndices.get(v)) {
					idoms.put(v, u);
				} else {
					idoms.put(v, p);
				}
			}
			
			semiDoms.remove(p);
		}
		
		for(int i=1; i <= size; i++) {
			N n = preOrder.get(i);
			if(idoms.get(n) != preOrder.get(semiIndices.get(n))) {
				idoms.put(n, idoms.get(idoms.get(n)));
			}
		}
	}
	
	private N calcSemiDom(N n) {
		propagate(n);
		return propagationMap.get(n);
	}
	
	private void propagate(N n) {
		LinkedList<N> wl = new LinkedList<>();
		wl.add(n);
		N anc = ancestors.get(n);
		
		while(ancestors.containsKey(anc)) {
			wl.push(anc);
			anc = ancestors.get(anc);
		}
		
		anc = wl.pop();
		int bottom = semiIndices.get(propagationMap.get(anc));
		
		while(!wl.isEmpty()) {
			N d = wl.pop();
			int current = semiIndices.get(propagationMap.get(d));
			if(current > bottom) {
				propagationMap.put(d, propagationMap.get(anc));
			} else {
				bottom = current;
			}
			
			anc = d;
		}
	}
}