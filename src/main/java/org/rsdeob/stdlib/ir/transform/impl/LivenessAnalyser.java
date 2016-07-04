package org.rsdeob.stdlib.ir.transform.impl;

import org.rsdeob.stdlib.cfg.edge.FlowEdge;
import org.rsdeob.stdlib.collections.graph.flow.FlowGraph;
import org.rsdeob.stdlib.ir.Local;
import org.rsdeob.stdlib.ir.StatementVisitor;
import org.rsdeob.stdlib.ir.expr.VarExpression;
import org.rsdeob.stdlib.ir.stat.CopyVarStatement;
import org.rsdeob.stdlib.ir.stat.Statement;
import org.rsdeob.stdlib.ir.transform.BackwardsFlowAnalyser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class LivenessAnalyser extends BackwardsFlowAnalyser<Statement, FlowEdge<Statement>, Map<Local, Boolean>> {

	/* live(x, b) where x is a variable and b is a block
	 *   if (OR live(x, p)) where p are the predecessors of b.
	 *   
	 * ... := f(x) , x is live before this statement
	 * 
	 * x := e      , x is dead before this statement
	 * 
	 * y := z      , x is not referenced, so the in and
	 *               out liveness of x is the same.
	 * 
	 * algorithm:
	 *     live(x0...xn) = false;
	 */
	
	private Map<Local, Boolean> initial;
	
	public LivenessAnalyser(FlowGraph<Statement, FlowEdge<Statement>> graph) {
		super(graph);
	}
	
	@Override
	protected void init() {
		initial = new HashMap<>();
		for(Statement stmt : graph.vertices()) {
			if(stmt instanceof CopyVarStatement) {
				VarExpression var = ((CopyVarStatement) stmt).getVariable();
				initial.put(var.getLocal(), Boolean.valueOf(false));
			}
			
			new StatementVisitor(stmt) {
				@Override
				public Statement visit(Statement s) {
					if(s instanceof VarExpression) {
						Local var = ((VarExpression) s).getLocal();
						initial.put(var, Boolean.valueOf(false));
					}
					return s;
				}
			}.visit();
		}
		
		super.init();
	}

	@Override
	public void removed(Statement n) {
		super.removed(n);
	}
	
	@Override
	public void update(Statement n) {
		super.update(n);
	}
	
	@Override
	public void replaced(Statement old, Statement n) {
		super.replaced(old, n);
	}
	
	@Override
	protected Map<Local, Boolean> newState() {
		return new HashMap<>(initial);
	}

	@Override
	protected Map<Local, Boolean> newEntryState() {
		return new HashMap<>(initial);
	}
	
	@Override
	protected void copy(Map<Local, Boolean> src, Map<Local, Boolean> dst) {
		for(Entry<Local, Boolean> e : src.entrySet()) {
			dst.put(e.getKey(), e.getValue());
		}
	}

	@Override
	protected void execute(Statement n, Map<Local, Boolean> out, Map<Local, Boolean> in) {
		if(x) System.out.println("    propagating " + n);
		
		for(Entry<Local, Boolean> e : out.entrySet()) {
			Local key = e.getKey();
			if(in.containsKey(key)) {
				if (x) System.out.println("      " + key + " := (" + e.getValue() + " || " + in.get(key) + ") = " + (e.getValue() || in.get(key)));
				in.put(key, e.getValue() || in.get(key));
			} else {
				if (x) System.out.println("      " + key + " := " + e.getValue());
				in.put(key, e.getValue());
			}
		}
		// do this before the uses because of
		// varx = varx statements (i had a dream about
		// it trust me it works).

		if(n instanceof CopyVarStatement) {
			in.put(((CopyVarStatement) n).getVariable().getLocal(), false);
		}
		
		new StatementVisitor(n) {
			@Override
			public Statement visit(Statement s) {
				if(s instanceof VarExpression) {
					Local var = ((VarExpression) s).getLocal();
					in.put(var, true);
				}
				return s;
			}
		}.visit();
	}

	@Override
	protected void merge(Map<Local, Boolean> in1, Map<Local, Boolean> in2, Map<Local, Boolean> out) {
		Set<Local> keys = new HashSet<>();
		keys.addAll(in1.keySet());
		keys.addAll(in2.keySet());
		
		for(Local key : keys) {
			if(in1.containsKey(key) && in2.containsKey(key)) {
				out.put(key, in1.get(key) || in2.get(key));
			} else if(!in1.containsKey(key)) {
				out.put(key, in2.get(key));
			} else if(!in2.containsKey(key)) {
				out.put(key, in1.get(key));
			}
		}
	}

	@Override
	protected boolean equals(Map<Local, Boolean> s1, Map<Local, Boolean> s2) {
		Set<Local> keys = new HashSet<>();
		keys.addAll(s1.keySet());
		keys.addAll(s2.keySet());
		
		for(Local key : keys) {
			if(!s1.containsKey(key) || !s2.containsKey(key)) {
				return false;
			}
			
			if(s1.get(key).booleanValue() != s2.get(key).booleanValue()) {
				return false;
			}
		}
		return true;
	}
}