package org.mapleir;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import org.mapleir.byteio.CompleteResolvingJarDumper;
import org.mapleir.deobimpl2.*;
import org.mapleir.ir.ControlFlowGraphDumper;
import org.mapleir.ir.cfg.BoissinotDestructor;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.code.Expr;
import org.mapleir.stdlib.IContext;
import org.mapleir.stdlib.call.CallTracer;
import org.mapleir.stdlib.collections.graph.GraphUtils;
import org.mapleir.stdlib.deob.ICompilerPass;
import org.mapleir.stdlib.klass.ClassTree;
import org.mapleir.stdlib.klass.InvocationResolver;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.topdank.byteengineer.commons.data.JarInfo;
import org.topdank.byteio.in.SingleJarDownloader;
import org.topdank.byteio.out.JarDumper;

public class Boot {

	private static Map<MethodNode, ControlFlowGraph> cfgs;
	private static long timer;
	private static Deque<String> sections;
	
	private static double lap() {
		long now = System.nanoTime();
		long delta = now - timer;
		timer = now;
		return (double)delta / 1_000_000_000L;
	}
	
	private static void section0(String endText, String sectionText) {
		if(sections.isEmpty()) {
			lap();
			System.out.println(sectionText);
		} else {
			/* remove last section. */
			sections.pop();
			System.out.printf(endText, lap());
			System.out.println("\n" + sectionText);
		}

		/* push the new one. */
		sections.push(sectionText);
	}
	
	private static void section(String text) {
		section0("...took %fs.%n", text);
	}
	
	public static void main2(String[] args) throws IOException {
		cfgs = new HashMap<>();
		sections = new LinkedList<>();
		/* if(args.length < 1) {
			System.err.println("Usage: <rev:int>");
			System.exit(1);
			return;
		} */
		
		File f = new File("res/allatori.jar");
		
		section("Preparing to run on " + f.getAbsolutePath());
		SingleJarDownloader<ClassNode> dl = new SingleJarDownloader<>(new JarInfo(f));
		dl.download();
		
		section("Building jar class hierarchy.");
		ClassTree tree = new ClassTree(dl.getJarContents().getClassContents());
		
		section("Initialising context.");

		InvocationResolver resolver = new InvocationResolver(tree);
		IContext cxt = new IContext() {
			@Override
			public ClassTree getClassTree() {
				return tree;
			}

			@Override
			public ControlFlowGraph getIR(MethodNode m) {
				if(cfgs.containsKey(m)) {
					return cfgs.get(m);
				} else {
					ControlFlowGraph cfg = ControlFlowGraphBuilder.build(m);
					cfgs.put(m, cfg);
					return cfg;
				}
			}

			@Override
			public Set<MethodNode> getActiveMethods() {
				return cfgs.keySet();
			}

			@Override
			public InvocationResolver getInvocationResolver() {
				return resolver;
			}
		};
		
		section("Expanding callgraph and generating cfgs.");
//		CallTracer tracer = new IRCallTracer(cxt) {
//			@Override
//			protected void processedInvocation(MethodNode caller, MethodNode callee, Expr call) {
//				/* the cfgs are generated by calling IContext.getIR()
//				 * in IRCallTracer.traceImpl(). */
//			}
//		};
//		for(MethodNode m : findEntries(tree)) {
//			tracer.trace(m);
//		}
		
		for(ClassNode cn : dl.getJarContents().getClassContents()) {
			for(MethodNode m : cn.methods) {
				cxt.getIR(m);
			}
		}
		
		section0("...generated " + cfgs.size() + " cfgs in %fs.%n", "Preparing to transform.");
		
		runPasses(cxt, getTransformationPasses());
			

//		for(Entry<MethodNode, ControlFlowGraph> e : cfgs.entrySet()) {
//			MethodNode mn = e.getKey();
//			ControlFlowGraph cfg = e.getValue();
//			
//			if(mn.toString().equals("a.akt(Lx;I)V")) {
//				BufferedWriter bw = new BufferedWriter(new FileWriter(new File("C:/Users/Bibl/Desktop/test224.txt")));
//				bw.write(cfg.toString());
//				bw.close();
//			}
//			
//		}
		
		section("Retranslating SSA IR to standard flavour.");
		for(Entry<MethodNode, ControlFlowGraph> e : cfgs.entrySet()) {
			MethodNode mn = e.getKey();
			ControlFlowGraph cfg = e.getValue();
			
			BoissinotDestructor.leaveSSA(cfg);
			cfg.getLocals().realloc(cfg);
			ControlFlowGraphDumper.dump(cfg, mn);
		}
		
		section("Rewriting jar.");
		JarDumper dumper = new CompleteResolvingJarDumper(dl.getJarContents());
		dumper.dump(new File("out/allatori_out.jar"));
		
		section("Finished.");
	}
	
	public static void main(String[] args) throws IOException {
		cfgs = new HashMap<>();
		sections = new LinkedList<>();
		/* if(args.length < 1) {
			System.err.println("Usage: <rev:int>");
			System.exit(1);
			return;
		} */
		
		File f = locateRevFile(129);
		
		section("Preparing to run on " + f.getAbsolutePath());
		SingleJarDownloader<ClassNode> dl = new SingleJarDownloader<>(new JarInfo(f));
		dl.download();
		
		section("Building jar class hierarchy.");
		ClassTree tree = new ClassTree(dl.getJarContents().getClassContents());
		
		section("Initialising context.");

		InvocationResolver resolver = new InvocationResolver(tree);
		IContext cxt = new IContext() {
			@Override
			public ClassTree getClassTree() {
				return tree;
			}

			@Override
			public ControlFlowGraph getIR(MethodNode m) {
				if(cfgs.containsKey(m)) {
					return cfgs.get(m);
				} else {
					ControlFlowGraph cfg = ControlFlowGraphBuilder.build(m);
					cfgs.put(m, cfg);
					m.cacheKey();
					if(m.toString().equals("u.e(Ljava/lang/String;Ljava/lang/String;II)Ljava/io/File;")) {
						GraphUtils.verifyCfg(cfg);
					}
					return cfg;
				}
			}

			@Override
			public Set<MethodNode> getActiveMethods() {
				return cfgs.keySet();
			}

			@Override
			public InvocationResolver getInvocationResolver() {
				return resolver;
			}
		};
		
		section("Expanding callgraph and generating cfgs.");
		CallTracer tracer = new IRCallTracer(cxt) {
			@Override
			protected void processedInvocation(MethodNode caller, MethodNode callee, Expr call) {
				/* the cfgs are generated by calling IContext.getIR()
				 * in IRCallTracer.traceImpl(). */
			}
		};
		for(MethodNode m : findEntries(tree)) {
			tracer.trace(m);
		}
		
		section0("...generated " + cfgs.size() + " cfgs in %fs.%n", "Preparing to transform.");
		
		runPasses(cxt, getTransformationPasses());
			

		for(Entry<MethodNode, ControlFlowGraph> e : cfgs.entrySet()) {
			MethodNode mn = e.getKey();
			ControlFlowGraph cfg = e.getValue();
			
			if(mn.toString().equals("a.akt(Lx;I)V")) {
				BufferedWriter bw = new BufferedWriter(new FileWriter(new File("C:/Users/Bibl/Desktop/test224.txt")));
				bw.write(cfg.toString());
				bw.close();
			}
			
		}
		
		section("Retranslating SSA IR to standard flavour.");
		for(Entry<MethodNode, ControlFlowGraph> e : cfgs.entrySet()) {
			MethodNode mn = e.getKey();
			ControlFlowGraph cfg = e.getValue();
			
			BoissinotDestructor.leaveSSA(cfg);
			cfg.getLocals().realloc(cfg);
			ControlFlowGraphDumper.dump(cfg, mn);
		}
		
		section("Rewriting jar.");
		JarDumper dumper = new CompleteResolvingJarDumper(dl.getJarContents());
		dumper.dump(new File("out/osb.jar"));
		
		section("Finished.");
	}
	
	private static void runPasses(IContext cxt, ICompilerPass[] passes) {
		List<ICompilerPass> completed = new ArrayList<>();
		ICompilerPass last = null;
		
		for(int i=0; i < passes.length; i++) {
			ICompilerPass p = passes[i];
			section0("...took %fs." + (i == 0 ? "%n" : ""), "Running " + p.getId());
			p.accept(cxt, last, completed);
			
			completed.add(p);
			last = p;
		}
	}
	
	private static ICompilerPass[] getTransformationPasses() {
		return new ICompilerPass[] {
				new CallgraphPruningPass(),
				new ConcreteStaticInvocationPass(),
				new MethodRenamerPass(),
//				new ClassRenamerPass(),
//				new FieldRenamerPass(),
				new ConstantParameterPass(),
				new ConstantExpressionReorderPass(),
				new FieldRSADecryptionPass(),
				new ConstantExpressionEvaluatorPass(),
				new DeadCodeEliminationPass()
		};
	}
	
	private static File locateRevFile(int rev) {
		return new File("res/gamepack" + rev + ".jar");
	}
	
	private static Set<MethodNode> findEntries(ClassTree tree) {
		Set<MethodNode> set = new HashSet<>();
		for(ClassNode cn : tree.getClasses().values())  {
			for(MethodNode m : cn.methods) {
				if(m.name.length() > 2) {
					set.add(m);
				}
			}
		}
		return set;
	}
}