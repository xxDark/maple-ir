package org.mapleir.ir.cfg;

import org.mapleir.ir.analysis.DominanceLivenessAnalyser;
import org.mapleir.ir.analysis.ExtendedDfs;
import org.mapleir.ir.analysis.SSADefUseMap;
import org.mapleir.ir.code.Opcode;
import org.mapleir.ir.code.expr.Expression;
import org.mapleir.ir.code.expr.PhiExpression;
import org.mapleir.ir.code.expr.VarExpression;
import org.mapleir.ir.code.stmt.Statement;
import org.mapleir.ir.code.stmt.copy.AbstractCopyStatement;
import org.mapleir.ir.code.stmt.copy.CopyPhiStatement;
import org.mapleir.ir.code.stmt.copy.CopyVarStatement;
import org.mapleir.ir.dot.ControlFlowGraphDecorator;
import org.mapleir.ir.dot.LivenessDecorator;
import org.mapleir.ir.locals.BasicLocal;
import org.mapleir.ir.locals.Local;
import org.mapleir.ir.locals.LocalsHandler;
import org.mapleir.stdlib.cfg.edge.FlowEdge;
import org.mapleir.stdlib.cfg.util.TabbedStringWriter;
import org.mapleir.stdlib.collections.ListCreator;
import org.mapleir.stdlib.collections.NullPermeableHashMap;
import org.mapleir.stdlib.collections.SetCreator;
import org.mapleir.stdlib.collections.graph.dot.BasicDotConfiguration;
import org.mapleir.stdlib.collections.graph.dot.DotConfiguration;
import org.mapleir.stdlib.collections.graph.dot.DotWriter;
import org.mapleir.stdlib.collections.graph.util.GraphUtils;
import org.mapleir.stdlib.ir.transform.Liveness;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mapleir.ir.dot.ControlFlowGraphDecorator.OPT_DEEP;

public class BoissinotDestructor implements Liveness<BasicBlock>, Opcode {
	// ============================================================================================================= //
	// ================================================== Debug crap =============================================== //
	// ============================================================================================================= //
	private final Set<Local> localsTest = new HashSet<>();
	@Override
	public Set<Local> in(BasicBlock b) {
		Set<Local> live = new HashSet<>();
		for (Local l : localsTest)
			if (resolver.isLiveIn(b, l))
				live.add(l);
		return live;
	}
	@Override
	public Set<Local> out(BasicBlock b) {
		Set<Local> live = new HashSet<>();
		for (Local l : localsTest)
			if (resolver.isLiveOut(b, l))
				live.add(l);
		return live;
	}

	private void updateLocalsTest(ParallelCopyVarStatement copy) {
		for(CopyPair p : copy.pairs) {
			localsTest.add(p.targ);
			localsTest.add(p.source);
		}
	}

	private final static boolean DO_VALUE_INTERFERENCE = true;
	private static final boolean DO_FACILITATE_COALESCE = true;

	public void testSequentialize() {
		AtomicInteger base = new AtomicInteger(0);
		Local a = new BasicLocal(base, 1, false);
		Local b = new BasicLocal(base, 2, false);
		Local c = new BasicLocal(base, 3, false);
		Local d = new BasicLocal(base, 4, false);
		Local e = new BasicLocal(base, 5, false);
		Local spill = new BasicLocal(base, 6, false);
		System.out.println("SEQ TEST START");
		ParallelCopyVarStatement pcvs = new ParallelCopyVarStatement();
		pcvs.pairs.add(new CopyPair(a, b, null));
		pcvs.pairs.add(new CopyPair(b, a, null));
		pcvs.pairs.add(new CopyPair(c, b, null));
		pcvs.pairs.add(new CopyPair(d, c, null));
		pcvs.pairs.add(new CopyPair(e, a, null));
		for (Statement stmt : pcvs) {
			if (stmt.getOpcode() == Opcode.LOCAL_LOAD)
				System.out.println(((VarExpression) stmt).getLocal());
		}
		List<CopyVarStatement> seqd = pcvs.sequentialize(spill);
		System.out.println("seq test: " + pcvs);
		for (CopyVarStatement cvs : seqd)
			System.out.println("  " + cvs);
	}

	private final ControlFlowGraph cfg;
	private final LocalsHandler locals;

	private SSADefUseMap defuse;
	private DominanceLivenessAnalyser resolver;
	private NullPermeableHashMap<Local, LinkedHashSet<Local>> values;

	private ExtendedDfs<BasicBlock> dom_dfs;
	private HashMap<Local, Local> equalAncIn;
	private HashMap<Local, Local> equalAncOut;

	private Map<Local, CongruenceClass> congruenceClasses;
	private Map<Local, Local> remap;

	public BoissinotDestructor(ControlFlowGraph cfg) {
		this.cfg = cfg;
		locals = cfg.getLocals();

		BasicDotConfiguration<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> config = new BasicDotConfiguration<>(DotConfiguration.GraphType.DIRECTED);
		DotWriter<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>> writer = new DotWriter<>(config, cfg);
		writer.add(new ControlFlowGraphDecorator().setFlags(OPT_DEEP)).setName("pre-destruct").export();

		// 1. Insert copies to enter CSSA.
		init();
		insertCopies();
		createDuChains();
		verify();

		localsTest.addAll(defuse.phiDefs.keySet());
		localsTest.addAll(defuse.uses.keySet());
		localsTest.addAll(defuse.defs.keySet());
		writer.add("liveness", new LivenessDecorator<ControlFlowGraph, BasicBlock, FlowEdge<BasicBlock>>().setLiveness(this)).setName("after-insert").export();

		// 2. Build value interference
		computeValueInterference();
		defuse.buildIndices(dom_dfs.getPreOrder());
		System.out.println();
		for (Entry<Local, Integer> e : defuse.defIndex.entrySet())
			System.out.println(e.getKey() + " " + e.getValue());
		System.out.println();
		System.out.println();

		// 3. Aggressively coalesce while in CSSA to leave SSA
		// 3a. Coalesce phi locals to leave CSSA (!!!)
		coalescePhis();

		localsTest.clear();
		localsTest.addAll(defuse.uses.keySet());
		localsTest.addAll(defuse.defs.keySet());

		// 3b. Coalesce the rest of the copies
		coalesceCopies();
		applyRemapping(remap);
		writer.remove("liveness").setName("after-coalesce").export();

		// 4. Sequentialize parallel copies
		sequentialize();
		writer.setName("after-sequentialize").export();
	}

	// ============================================================================================================= //
	// ================================================ Insert copies ============================================== //
	// ============================================================================================================= //
	private void init() {
		// Sanity check
		for(BasicBlock b : cfg.vertices()) {
			for(Statement stmt : b)  {
				if(stmt instanceof CopyPhiStatement) {
					CopyPhiStatement copy = (CopyPhiStatement) stmt;
					for (Expression arg : copy.getExpression().getArguments().values())
						if (arg.getOpcode() != Opcode.LOCAL_LOAD)
							throw new IllegalArgumentException("Non-variable expression in phi: " + copy);
				}
			}
		}

		// Create dominance
		resolver = new DominanceLivenessAnalyser(cfg, null);
	}

	private void insertCopies() {
		for(BasicBlock b : cfg.vertices())
			insertCopies(b);
	}

	private void insertCopies(BasicBlock b) {
		NullPermeableHashMap<BasicBlock, List<PhiRes>> wl = new NullPermeableHashMap<>(new ListCreator<>());
		ParallelCopyVarStatement dst_copy = new ParallelCopyVarStatement();

		// given a phi: L0: x0 = phi(L1:x1, L2:x2)
		//  insert the copies:
		//   L0: x0 = x3 (at the end of L0)
		//   L1: x4 = x1
		//   L2: x5 = x2
		//  and change the phi to:
		//   x3 = phi(L1:x4, L2:x5)

		for(Statement stmt : b) {
			if(stmt.getOpcode() != Opcode.PHI_STORE) {
				break;
			}

			CopyPhiStatement copy = (CopyPhiStatement) stmt;
			PhiExpression phi = copy.getExpression();

			// for every xi arg of the phi from pred Li, add it to the worklist
			// so that we can parallelise the copy when we insert it.
			for(Entry<BasicBlock, Expression> e : phi.getArguments().entrySet()) {
				BasicBlock h = e.getKey();
				VarExpression v = (VarExpression) e.getValue();
				PhiRes r = new PhiRes(copy.getVariable().getLocal(), phi, h, v.getLocal(), v.getType());
				wl.getNonNull(h).add(r);
			}

			// for each x0, where x0 is a phi copy target, create a new
			// variable z0 for a copy x0 = z0 and replace the phi
			// copy target to z0.
			Local x0 = copy.getVariable().getLocal();
			Local z0 = cfg.getLocals().makeLatestVersion(x0);
			dst_copy.pairs.add(new CopyPair(x0, z0, copy.getVariable().getType())); // x0 = z0
			copy.getVariable().setLocal(z0); // z0 = phi(...)
		}

		// resolve
		if(dst_copy.pairs.size() > 0)
			insertStart(b, dst_copy);

		for(Entry<BasicBlock, List<PhiRes>> e : wl.entrySet()) {
			BasicBlock p = e.getKey();
			ParallelCopyVarStatement copy = new ParallelCopyVarStatement();

			for(PhiRes r : e.getValue()) {
				// for each xi source in a phi, create a new variable zi,
				// and insert the copy zi = xi in the pred Li. then replace
				// the phi arg from Li with zi.

				Local xi = r.l;
				Local zi = cfg.getLocals().makeLatestVersion(xi);
				copy.pairs.add(new CopyPair(zi, xi, r.type));

				// we consider phi args to be used in the pred
				//  instead of the block where the phi is, so
				//  we need to update the def/use maps here.

				r.phi.setArgument(r.pred, new VarExpression(zi, r.type));
			}

			if (DO_FACILITATE_COALESCE) {
				// replace uses of xi with zi in dominated successors to faciltate coalescing
				for (BasicBlock succ : resolver.sdoms.getNonNull(p)) {
					for (Statement stmt : succ) {
						for (Statement child : stmt) {
							if (child.getOpcode() == Opcode.LOCAL_LOAD) {
								VarExpression var = ((VarExpression) child);
								Local local = var.getLocal();
								for (CopyPair pair : copy.pairs) {
									if (local == pair.source)
										var.setLocal(pair.targ);
								}
							}
						}
					}
				}
			}

			insertEnd(p, copy);
		}
	}

	private void insertStart(BasicBlock b, ParallelCopyVarStatement copy) {
		if(b.isEmpty()) {
			b.add(copy);
		} else {
			// insert after phi.
			int i;
			for (i = 0; i < b.size() && b.get(i).getOpcode() == Opcode.PHI_STORE; i++);
			b.add(i, copy);
		}

		updateLocalsTest(copy);
		System.out.println("insert-start: " + copy);
	}

	private void insertEnd(BasicBlock b, ParallelCopyVarStatement copy) {
		System.out.println("insert-end: " + copy);
		if(b.isEmpty())
			b.add(copy);
		else if (!b.get(b.size() - 1).canChangeFlow())
			b.add(copy);
		else
			b.add(b.size() - 1, copy);
	}

	private void createDuChains() {
		defuse = new SSADefUseMap(cfg) {
			@Override
			protected void build(BasicBlock b, Statement stmt) {
				if (stmt instanceof ParallelCopyVarStatement) {
					ParallelCopyVarStatement copy = (ParallelCopyVarStatement) stmt;
					for (CopyPair pair : copy.pairs) {
						defs.put(pair.targ, b);
						uses.getNonNull(pair.source).add(b);
					}
				} else {
					super.build(b, stmt);
				}
			}

			@Override
			protected int buildIndex(BasicBlock b, Statement stmt, int index) {
				if (stmt instanceof ParallelCopyVarStatement) {
					ParallelCopyVarStatement copy = (ParallelCopyVarStatement) stmt;
					for (CopyPair pair : copy.pairs) {
						defIndex.put(pair.targ, index);
						lastUseIndex.getNonNull(pair.source).put(b, index);
						index++;
					}
				} else {
					index = super.buildIndex(b, stmt, index);
				}
				return index;
			}
		};
		defuse.compute();

		resolver.setDefuse(defuse);
	}

	private void verify() {
		Map<Local, BasicBlock> defs = new HashMap<>();
		NullPermeableHashMap<Local, Set<BasicBlock>> uses = new NullPermeableHashMap<>(new SetCreator<>());

		for (BasicBlock b : cfg.vertices()) {
			for (Statement stmt : b) {
				if(stmt instanceof ParallelCopyVarStatement) {
					ParallelCopyVarStatement pcopy = (ParallelCopyVarStatement) stmt;
					for(CopyPair p : pcopy.pairs) {
//						System.out.println("def " + p.targ + " = " + p.source + "  in " + b.getId());
						defs.put(p.targ, b);
						uses.getNonNull(p.source).add(b);
					}
				} else {
					boolean _phi = false;
					if (stmt instanceof AbstractCopyStatement) {
						AbstractCopyStatement copy = (AbstractCopyStatement) stmt;
						Local l = copy.getVariable().getLocal();
						defs.put(l, b);

						Expression e = copy.getExpression();
						if (e instanceof PhiExpression) {
							_phi = true;
							PhiExpression phi = (PhiExpression) e;
							for (Entry<BasicBlock, Expression> en : phi.getArguments().entrySet()) {
								Local ul = ((VarExpression) en.getValue()).getLocal();
								uses.getNonNull(ul).add(en.getKey());
							}
						}
					}

					if (!_phi) {
						for (Statement s : stmt) {
							if (s instanceof VarExpression) {
								Local l = ((VarExpression) s).getLocal();
								uses.getNonNull(l).add(b);
							}
						}
					}
				}
			}
		}

		Set<Local> set = new HashSet<>();
		set.addAll(defs.keySet());
		set.addAll(defuse.defs.keySet());

		for(Local l : set) {
			BasicBlock b1 = defs.get(l);
			BasicBlock b2 = defuse.defs.get(l);

			if(b1 != b2) {
				System.err.println(cfg);
				System.err.println("Defs:");
				System.err.println(b1 + ", " + b2 + ", " + l);
				throw new RuntimeException();
			}
		}

		set.clear();
		set.addAll(uses.keySet());
		set.addAll(defuse.uses.keySet());

		for(Local l : set) {
			Set<BasicBlock> s1 = uses.getNonNull(l);
			Set<BasicBlock> s2 = defuse.uses.getNonNull(l);
			if(!s1.equals(s2)) {
				System.err.println(cfg);
				System.err.println("Uses:");
				System.err.println(GraphUtils.toBlockArray(s1));
				System.err.println(GraphUtils.toBlockArray(s2));
				System.err.println(l);
				throw new RuntimeException();
			}
		}
	}

	// ============================================================================================================= //
	// ============================================ Value interference ============================================= //
	// ============================================================================================================= //
	private void computeValueInterference() {
		values = new NullPermeableHashMap<>(local -> {
			LinkedHashSet<Local> valueClass = new LinkedHashSet<>();
			valueClass.add(local);
			return valueClass;
		});
		FastBlockGraph dom_tree = new FastBlockGraph();
		resolver.domc.makeTree(dom_tree);
		dom_tree.getEntries().addAll(cfg.getEntries());

//		BasicDotConfiguration<FastBlockGraph, BasicBlock, FlowEdge<BasicBlock>> config = new BasicDotConfiguration<>(DotConfiguration.GraphType.DIRECTED);
//		DotWriter<FastBlockGraph, BasicBlock, FlowEdge<BasicBlock>> writer = new DotWriter<>(config, dom_tree);
//		writer.removeAll().setName("domtree").export();

		// Compute dominance DFS
		dom_dfs = new ExtendedDfs<>(dom_tree, cfg.getEntries().iterator().next(), ExtendedDfs.POST | ExtendedDfs.PRE);

		List<BasicBlock> postorder = dom_dfs.getPostOrder();
		for (int i = postorder.size() - 1; i >= 0; i--) {
			BasicBlock bl = postorder.get(i);
			for (Statement stmt : bl) {
				if (stmt instanceof CopyVarStatement) {
					CopyVarStatement copy = (CopyVarStatement) stmt;
					Expression e = copy.getExpression();
					Local b = copy.getVariable().getLocal();

					if (!copy.isSynthetic() && e instanceof VarExpression) {
						LinkedHashSet<Local> valueClass = values.get(((VarExpression) e).getLocal());
						valueClass.add(b);
						values.put(b, valueClass);
					} else {
						values.getNonNull(b);
					}
				} else if (stmt instanceof CopyPhiStatement) {
					CopyPhiStatement copy = (CopyPhiStatement) stmt;
					values.getNonNull(copy.getVariable().getLocal());
				} else if (stmt instanceof ParallelCopyVarStatement) {
					ParallelCopyVarStatement copy = (ParallelCopyVarStatement) stmt;
					for (CopyPair p : copy.pairs) {
						LinkedHashSet<Local> valueClass = values.get(p.source);
						valueClass.add(p.targ);
						values.put(p.targ, valueClass);
					}
				}
			}
		}

		System.out.println("\nvalues:");
		Set<LinkedHashSet<Local>> seen = new HashSet<>();
		for (LinkedHashSet<Local> valueClass : values.values()) {
			if (!seen.contains(valueClass)) {
				seen.add(valueClass);
				System.out.println("  " + valueClass);
			}
		}
		System.out.println();
	}

	// ============================================================================================================= //
	// ================================================= Coalesce ================================================== //
	// ============================================================================================================= //
	// Initialize ccs based on phis and drop phi statements
	private void coalescePhis() {
		congruenceClasses = new HashMap<>();
		remap = new HashMap<>();

		for (Entry<Local, CopyPhiStatement> e : defuse.phiDefs.entrySet()) {
			Local l1 = e.getKey();
			BasicBlock b = defuse.defs.get(l1);
			// since we are now in csaa, phi locals never interfere and are in the same congruence class.
			// therefore we can coalesce them all together and drop phis. with this, we leave cssa.
			PhiExpression phi = e.getValue().getExpression();

			CongruenceClass phiConClass = new CongruenceClass();
			phiConClass.add(l1);
			congruenceClasses.put(l1, phiConClass);

			for(Expression ex : phi.getArguments().values()) {
				VarExpression v = (VarExpression) ex;
				Local l = v.getLocal();
				phiConClass.add(l);
				congruenceClasses.put(l, phiConClass);
			}
			System.out.println("phi conclass: " + phiConClass);

			// we can simply drop all the phis without further consideration
			for (Iterator<Statement> it = b.iterator(); it.hasNext();) {
				if (it.next().getOpcode() == Opcode.PHI_STORE)
					it.remove();
				else
					break;
			}
		}

		defuse.phiDefs.clear();
		System.out.println();
	}

	// Coalesce parallel and standard copies based on value interference, dropping coalesced copies
	private void coalesceCopies() {
		equalAncIn = new HashMap<>();
		equalAncOut = new HashMap<>();
		// now for each copy check if lhs and rhs congruence classes do not interfere.
		// if they do not interfere merge the conClasses and those two vars can be coalesced. delete the copy.
		for (BasicBlock b : dom_dfs.getPreOrder()) {
			for (Iterator<Statement> it = b.iterator(); it.hasNext(); ) {
				Statement stmt = it.next();
				if (stmt instanceof CopyVarStatement) {
					CopyVarStatement copy = (CopyVarStatement) stmt;
					if (!copy.isSynthetic() && copy.getExpression() instanceof VarExpression) {
						Local lhs = copy.getVariable().getLocal();
						Local rhs = ((VarExpression) copy.getExpression()).getLocal();
						if (tryCoalesceCopyValue(lhs, rhs) || tryCoalesceCopySharing(lhs, rhs))
							it.remove();
					}
				} else if (stmt.getOpcode() == -1) {
					// we need to do it for each one. if all of the copies are removed then remove the pcvs
					ParallelCopyVarStatement copy = (ParallelCopyVarStatement) stmt;
					for (Iterator<CopyPair> pairIter = copy.pairs.iterator(); pairIter.hasNext(); ) {
						CopyPair pair = pairIter.next();
						Local lhs = pair.targ, rhs = pair.source;
						if (tryCoalesceCopyValue(lhs, rhs) || tryCoalesceCopySharing(lhs, rhs))
							pairIter.remove();
					}
					if (copy.pairs.isEmpty())
						it.remove();
				}
			}
		}

		System.out.println("Final congruence classes:");
		for (Entry<Local, CongruenceClass> e : congruenceClasses.entrySet())
			System.out.println(e.getKey() + " => " + e.getValue());
		System.out.println();
		// ok NOW we remap to avoid that double remap issue
		for (Entry<Local, CongruenceClass> e : congruenceClasses.entrySet())
			remap.put(e.getKey(), e.getValue().first());
	}

	// Process the copy a = b. Returns true if a and b can be coalesced via value.
	private boolean tryCoalesceCopyValue(Local a, Local b) {
		CongruenceClass conClassA = getCongruenceClass(a);
		CongruenceClass conClassB = getCongruenceClass(b);

		System.out.println("  Check intersection: " + a + " \u2208 " + conClassA + " vs " + b + " \u2208 " + conClassB + ": ");
		if (conClassA.size() == 1 && conClassB.size() == 1)
			return checkInterfereSingle(conClassA, conClassB);

		if (checkInterfere(conClassA, conClassB)) {
			System.out.println("  => true");
			return false;
		}
		System.out.println("  => false");

		// merge congruence classes
		merge(conClassA, conClassB);
		return true;
	}

	private CongruenceClass getCongruenceClass(Local l) {
		if (congruenceClasses.containsKey(l))
			return congruenceClasses.get(l);
		CongruenceClass conClass = new CongruenceClass();
		conClass.add(l);
		congruenceClasses.put(l, conClass);
		return conClass;
	}

	// Process the copy a = b. Returns true of a and b can be coalesced via sharing.
	private boolean tryCoalesceCopySharing(Local a, Local b) {
		CongruenceClass pccX = getCongruenceClass(a);
		CongruenceClass pccY = getCongruenceClass(b);
		for (Local c : values.get(a)) {
			if (c == b || c == a || !checkPreDomOrder(c, a) || !intersect(a, c))
				continue;
			System.out.println("sharing candidates " + a + " " + c);
			CongruenceClass pccZ = getCongruenceClass(c);

			// If X = Z and X != Y, the copy is redundant.
			if (pccX == pccZ && pccX != pccY) {
				System.out.println("coalescing " + a + " " + c + " sharing");
				return true;
			}

			// If X, Y, and Z are all different and if a and c are coalescable via value then the copy is redundant
			// after a and b have been coalesced as c already has the correct value.
			if (pccY != pccX && pccY != pccZ && pccX != pccZ && tryCoalesceCopyValue(a, c)) {
				System.out.println("coalescing " + a + " " + c + " sharing2");
				return true;
			}
		}
		return false;
	}

	private boolean checkPreDomOrder(Local x, Local y) {
		return defuse.defIndex.get(x) < defuse.defIndex.get(y);
	}

	// Flatten ccs so that each local in each cc is replaced with a new representative local.
	private void applyRemapping(Map<Local, Local> remap) {
		// defuse can be used here to speed things up. TODO
		for(BasicBlock b : cfg.vertices()) {
			for (Iterator<Statement> it = b.iterator(); it.hasNext(); ) {
				Statement stmt = it.next();
				if (stmt instanceof ParallelCopyVarStatement) {
					ParallelCopyVarStatement copy = (ParallelCopyVarStatement) stmt;
					for (Iterator<CopyPair> it2 = copy.pairs.iterator(); it2.hasNext(); ) {
						CopyPair p = it2.next();
						p.source = remap.getOrDefault(p.source, p.source);
						p.targ = remap.getOrDefault(p.targ, p.targ);
						if (p.source == p.targ)
							it2.remove();
					}
					if (copy.pairs.isEmpty())
						it.remove();
				} else if (stmt instanceof CopyVarStatement) {
					AbstractCopyStatement copy = (AbstractCopyStatement) stmt;
					VarExpression v = copy.getVariable();
					v.setLocal(remap.getOrDefault(v.getLocal(), v.getLocal()));
					if (copy.isSynthetic() && copy.getExpression().getOpcode() == Opcode.LOCAL_LOAD)
						if (((VarExpression) copy.getExpression()).getLocal() == v.getLocal())
							it.remove();
				} else if (stmt instanceof CopyPhiStatement) {
					throw new IllegalArgumentException("Phi copy still in block?");
				}

				for (Statement s : stmt) {
					if (s.getOpcode() == Opcode.LOCAL_LOAD) {
						VarExpression v = (VarExpression) s;
						v.setLocal(remap.getOrDefault(v.getLocal(), v.getLocal()));
					}
				}
			}
		}

		for (Entry<Local, Local> e : remap.entrySet()) {
//			System.out.println(e.getKey() + " -> " + e.getValue());
			defuse.defs.remove(e.getKey());
			defuse.uses.remove(e.getKey());
		}
	}

	// ============================================================================================================= //
	// =============================================== Interference ================================================ //
	// ============================================================================================================= //
	private boolean checkInterfereSingle(CongruenceClass red, CongruenceClass blue) {
		Local a = red.first();
		Local b = blue.first();
		if (checkPreDomOrder(a, b)) { // we want a > b in dom order (b is parent)
			Local c = a;
			a = b;
			b = c;
		}
		if (!DO_VALUE_INTERFERENCE)
			return intersect(a, b);
		if (intersect(a, b) && values.getNonNull(a) != values.getNonNull(b)) {
			return true;
		} else {
			equalAncIn.put(a, b);
			red.add(b);
			congruenceClasses.put(b, red);
			return false;
		}
	}

	private boolean checkInterfere(CongruenceClass red, CongruenceClass blue) {
		class LocalInfo {
			final Local l;
			final CongruenceClass conClass;

			LocalInfo(Local local, CongruenceClass congruenceClass) {
				l = local;
				conClass = congruenceClass;
			}

			@Override
			public String toString() {
				return l.toString() + " \u2208 " + conClass;
			}
		}

		Stack<LocalInfo> dom = new Stack<>();
		int nr = 0, nb = 0;
		Local ir = red.first(), ib = blue.first();
		Local lr = red.last(), lb = blue.last(); // end sentinels
		boolean redHasNext = true, blueHasNext = true;
		equalAncOut.put(ir, null); // these have no parents so we have to manually init them
		equalAncOut.put(ib, null);
		do {
			LocalInfo current;
			if (!blueHasNext || (redHasNext && checkPreDomOrder(ir, ib))) {
//				System.out.println("    ir= " + ir);
				current = new LocalInfo(ir, red); // current = red[ir++]
				nr++;
				if (redHasNext = ir != lr)
					ir = red.higher(ir);
//				System.out.println("    Red next, current=" + current + ", hasNext=" + redHasNext);
			} else {
//				System.out.println("ib= " + ib);
				current = new LocalInfo(ib, blue); // current = blue[ib++]
				nb++;
				if (blueHasNext = ib != lb)
					ib = blue.higher(ib);
//				System.out.println("    Blue next, current=" + current + ", hasNext=" + blueHasNext);
			}

			if (!dom.isEmpty()) {
				LocalInfo parent;
				do {
					parent = dom.pop();
					if (parent.conClass == red)
						nr--;
					else if (parent.conClass == blue)
						nb--;
				} while (!dom.isEmpty() && !checkPreDomOrder(parent.l, current.l));

//				System.out.println("    Check " + current + " vs " + parent + ":");
				if (interference(current.l, parent.l, current.conClass == parent.conClass)) {
//					System.out.println("      => true");
					return true;
				}
			}
			dom.push(current);
//			System.out.println("    (nr, nb) = (" + nr + ", " + nb + ")");
		} while ((redHasNext && nb > 0) || (blueHasNext && nr > 0) || (redHasNext && blueHasNext));

		return false;
	}

	private boolean interference(Local a, Local b, boolean sameConClass) {
		if (DO_VALUE_INTERFERENCE) {
//			System.out.println("      anc-in: " + equalAncIn);
//			System.out.println("      anc-out: " + equalAncOut);
			equalAncOut.put(a, null);
			if (sameConClass) {
				b = equalAncOut.get(b);
//				System.out.println("      actually " + a + " vs " + b);
			}

			Local tmp = b;
			while (tmp != null && !intersect(a, tmp)) {
//				System.out.println("      traverse " + tmp);
				tmp = equalAncIn.get(tmp);
			}
			if (values.getNonNull(a) != values.getNonNull(b)) {
//				System.out.println("      different values " + tmp);
				return tmp != null;
			} else {
//				System.out.println("      same values " + tmp);
				equalAncOut.put(a, tmp);
				return false;
			}
		} else {
			return intersect(a, b);
		}
	}

	private boolean intersect(Local a, Local b) {
		if (a == b) {
			for (Entry<Local, CongruenceClass> e : congruenceClasses.entrySet())
				System.err.println(e.getKey() + " in " + e.getValue());
			throw new IllegalArgumentException("me too thanks: " + a);
		}
		if (checkPreDomOrder(a, b))
			throw new IllegalArgumentException("b should dom a");
		if (!checkPreDomOrder(b, a)) // dom = b ; def = a
			throw new IllegalArgumentException("this shouldn't happen???");

		BasicBlock defA = defuse.defs.get(a);
		if (resolver.isLiveOut(defA, b)) // if it's liveOut it definitely intersects
			return true;
		if (!resolver.isLiveIn(defA, b) && defA != defuse.defs.get(b)) // defA == defB or liveIn to intersect
			return false;
		// ambiguous case. we need to check if use(dom) occurs after def(def), in that case it interferes. otherwise no
		int domUseIndex = defuse.lastUseIndex.get(b).get(defA);
		int defDefIndex = defuse.defIndex.get(a);
		return domUseIndex > defDefIndex;
	}

	private void merge(CongruenceClass conClassA, CongruenceClass conClassB) {
		System.out.println("    pre-merge: " + conClassA);
		conClassA.addAll(conClassB);
		for (Local l : conClassB)
			congruenceClasses.put(l, conClassA);
		System.out.println("    post-merge: " + conClassA);

		for (Local l : conClassA) {
			Local in = equalAncIn.get(l);
			Local out = equalAncOut.get(l);
			if (in != null && out != null)
				equalAncIn.put(l, checkPreDomOrder(in, out) ? in : out);
			else
				equalAncIn.put(l, in != null ? in : out != null ? out : null);
		}
	}


	// ============================================================================================================= //
	// ============================================== Sequentialize ================================================ //
	// ============================================================================================================= //
	private void sequentialize() {
		for (BasicBlock b : cfg.vertices())
			sequentialize(b);
	}

	private void sequentialize(BasicBlock b) {
		// TODO: just rebuild the instruction list
		LinkedHashMap<ParallelCopyVarStatement, Integer> p = new LinkedHashMap<>();
		for (int i = 0; i < b.size(); i++) {
			Statement stmt = b.get(i);
			if (stmt instanceof ParallelCopyVarStatement)
				p.put((ParallelCopyVarStatement) stmt, i);
		}

		if (p.isEmpty())
			return;
		int indexOffset = 0;
		Local spill = cfg.getLocals().makeLatestVersion(p.entrySet().iterator().next().getKey().pairs.get(0).targ);
		for (Entry<ParallelCopyVarStatement, Integer> e : p.entrySet()) {
			ParallelCopyVarStatement pcvs = e.getKey();
			int index = e.getValue();
//			System.out.println(b + " " + pcvs + " " + e.getValue());
			if (pcvs.pairs.size() == 0)
				throw new IllegalArgumentException("pcvs is empty");
			else if (pcvs.pairs.size() == 1) { // constant sequentialize for trivial parallel copies
				CopyPair pair = pcvs.pairs.get(0);
				CopyVarStatement newCopy = new CopyVarStatement(new VarExpression(pair.targ, pair.type), new VarExpression(pair.source, pair.type));
				b.set(index + indexOffset, newCopy);
			} else {
				List<CopyVarStatement> sequentialized = pcvs.sequentialize(spill);
				b.remove(index + indexOffset--);
				// warning: O(N^2) operation
				for (CopyVarStatement cvs : sequentialized) { // warning: O(N^2) operation
//					System.out.println("  " + cvs + " @ " + index + "+" + indexOffset);
					b.add(index + ++indexOffset, cvs);
				}
			}
		}
	}

	private class PhiRes {
		final Local target;
		final PhiExpression phi;
		final BasicBlock pred;
		final Local l;
		final Type type;

		PhiRes(Local target, PhiExpression phi, BasicBlock src, Local l, Type type) {
			this.target = target;
			this.phi = phi;
			pred = src;
			this.l = l;
			this.type = type;
		}

		@Override
		public String toString() {
			return String.format("targ=%s, p=%s, l=%s(type=%s)", target, pred, l, type);
		}
	}

	private class CopyPair {
		Local targ;
		Local source;
		Type type;

		CopyPair(Local dst, Local src, Type type) {
			targ = dst;
			source = src;
			this.type = type;
		}

		@Override
		public String toString() {
			return targ + " =P= " + source + " (" + type + ")";
		}
	}

	private class ParallelCopyVarStatement extends Statement {
		final List<CopyPair> pairs;

		ParallelCopyVarStatement() {
			super(-1);
			pairs = new ArrayList<>();
		}

		ParallelCopyVarStatement(List<CopyPair> pairs) {
			super(-1);
			this.pairs = pairs;
		}

		@Override
		public void onChildUpdated(int ptr) {
		}

		@Override
		public void toString(TabbedStringWriter printer) {
			printer.print("PARALLEL(");

			for (Iterator<CopyPair> it = pairs.iterator(); it.hasNext(); ) {
				CopyPair p = it.next();
				printer.print(p.targ.toString());
//				printer.print("(" + p.type + ")");

				if(it.hasNext()) {
					printer.print(", ");
				}
			}

			printer.print(") = (");

			for (Iterator<CopyPair> it = pairs.iterator(); it.hasNext(); ) {
				CopyPair p = it.next();
				printer.print(p.source.toString());
//				printer.print("(" + p.type + ")");

				if(it.hasNext()) {
					printer.print(", ");
				}
			}

			printer.print(")");
		}

		private List<CopyVarStatement> sequentialize(Local spill) {
			Stack<Local> ready = new Stack<>();
			Stack<Local> to_do = new Stack<>();
			Map<Local, Local> loc = new HashMap<>();
			Map<Local, Local> values = new HashMap<>();
			Map<Local, Local> pred = new HashMap<>();
			Map<Local, Type> types = new HashMap<>();
			pred.put(spill, null);

			for (CopyPair pair : pairs) { // initialization
				loc.put(pair.targ, null);
				loc.put(pair.source, null);
				values.put(pair.targ, pair.targ);
				values.put(pair.source, pair.source);
				types.put(pair.targ, pair.type);
				types.put(pair.source, pair.type);
			}

			for (CopyPair pair : pairs) {
				loc.put(pair.source, pair.source); // needed and not copied yet
				pred.put(pair.targ, pair.source); // unique predecessor
				to_do.push(pair.targ); // copy into b to be done
			}

			for (CopyPair pair : pairs) {
				if (!loc.containsKey(pair.targ))
					throw new IllegalStateException("this shouldn't happen");
				if (loc.get(pair.targ) == null) // b is not used and can be overwritten
					ready.push(pair.targ);
			}

			List<CopyVarStatement> result = new ArrayList<>();
			while (!to_do.isEmpty()) {
				while (!ready.isEmpty()) {
					Local b = ready.pop(); // pick a free location
					Local a = pred.get(b); // available in c
					Local c = loc.get(a);
					if ((!types.containsKey(b) && b != spill) || (!types.containsKey(c) && c != spill))
						throw new IllegalStateException("this shouldn't happen " + b + " " + c);

					VarExpression varB = new VarExpression(b, types.get(b)); // generate the copy b = c
					VarExpression varC = new VarExpression(c, types.get(b));
					result.add(new CopyVarStatement(varB, varC));
					values.put(b, values.get(c));

					loc.put(a, b);
					if (a == c && pred.get(a) != null) {
						if (!pred.containsKey(a))
							throw new IllegalStateException("this shouldn't happen");
						ready.push(a); // just copied, can be overwritten
					}
				}

				Local b = to_do.pop();
				if (values.get(b) != values.get(loc.get(pred.get(b)))) {
//					System.out.println("  spill " + b + " (" + b + " vs " + loc.get(pred.get(b)).toString() + ")" + " (" + values.get(b) + " vs " + values.get(loc.get(pred.get(b))) + ")");
					if (!types.containsKey(b))
						throw new IllegalStateException("this shouldn't happen");
					VarExpression varN = new VarExpression(spill, types.get(b)); // generate copy n = b
					VarExpression varB = new VarExpression(b, types.get(b));
					result.add(new CopyVarStatement(varN, varB));
					values.put(spill, values.get(b));
					loc.put(b, spill);
					ready.push(b);
				}
			}

			return result;
		}

		@Override
		public void toCode(MethodVisitor visitor, ControlFlowGraph cfg) {
			throw new UnsupportedOperationException("Synthetic");
		}

		@Override
		public boolean canChangeFlow() {
			return false;
		}

		@Override
		public boolean canChangeLogic() {
			return false;
		}

		@Override
		public boolean isAffectedBy(Statement stmt) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Statement copy() {
			return new ParallelCopyVarStatement(new ArrayList<>(pairs));
		}

		@Override
		public boolean equivalent(Statement s) {
			return s instanceof ParallelCopyVarStatement && ((ParallelCopyVarStatement) s).pairs.equals(pairs);
		}
	}

	private class CongruenceClass extends TreeSet<Local> {
		CongruenceClass() {
			super(new Comparator<Local>() {
				@Override
				public int compare(Local o1, Local o2) {
					if (o1 == o2)
						return 0;
					return checkPreDomOrder(o1, o2)? -1 : 1;
				}
			});
		}
	}
}