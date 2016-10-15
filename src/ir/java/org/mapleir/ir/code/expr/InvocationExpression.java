package org.mapleir.ir.code.expr;

import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.stmt.Statement;
import org.mapleir.stdlib.util.TabbedStringWriter;
import org.mapleir.stdlib.util.TypeUtils;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class InvocationExpression extends Expression {

	private int callType;
	private Expression[] argumentExpressions;
	private String owner;
	private String name;
	private String desc;

	public InvocationExpression(int opcode, Expression[] argumentExpressions, String owner, String name, String desc) {
		super(INVOKE);
		callType = opcode;
		this.argumentExpressions = argumentExpressions;
		this.owner = owner;
		this.name = name;
		this.desc = desc;

		for (int i = 0; i < argumentExpressions.length; i++) {
			overwrite(argumentExpressions[i], i);
		}
	}
	
	public Expression getInstanceExpression() {
		if(callType == Opcodes.INVOKESTATIC) {
			return null;
		} else {
			return argumentExpressions[0];
		}
	}

	public int getCallType() {
		return callType;
	}

	public void setCallType(int callType) {
		this.callType = callType;
	}
	
	public Expression[] getParameterArguments() {
		int i = (callType == Opcodes.INVOKESTATIC) ? 0 : -1;
		Expression[] exprs = new Expression[argumentExpressions.length + i];
		i = Math.abs(i);
		for(int j=0; j < exprs.length; j++) {
			exprs[j] = argumentExpressions[j + i];
		}
		return exprs;
	}

	public Expression[] getArgumentExpressions() {
		return argumentExpressions;
	}

	public void updateArgument(int index, Expression argument) {
		if (index < 0 || (index) >= argumentExpressions.length) {
			throw new ArrayIndexOutOfBoundsException();
		}
		
		argumentExpressions[index] = argument;
		overwrite(argument, index);
	}

	public void setArgumentExpressions(Expression[] argumentExpressions) {
		if (callType != Opcodes.INVOKESTATIC && argumentExpressions.length <= 0) {
			throw new ArrayIndexOutOfBoundsException();
		}

		if (argumentExpressions.length < this.argumentExpressions.length) {
			setChildPointer(0);
			while (read(0) != null) {
				deleteAt(getChildPointer());
			}
		}
		this.argumentExpressions = argumentExpressions;
		for (int i = 0; i < argumentExpressions.length; i++) {
			overwrite(argumentExpressions[i], i);
		}
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}

	@Override
	public Expression copy() {
		Expression[] arguments = new Expression[argumentExpressions.length];
		for (int i = 0; i < arguments.length; i++) {
			arguments[i] = argumentExpressions[i].copy();
		}
		return new InvocationExpression(callType, arguments, owner, name, desc);
	}

	@Override
	public Type getType() {
		return Type.getReturnType(desc);
	}

	@Override
	public void onChildUpdated(int ptr) {
		updateArgument(ptr, (Expression) read(ptr));
	}

	@Override
	public Precedence getPrecedence0() {
		return Precedence.METHOD_INVOCATION;
	}

	@Override
	public void toString(TabbedStringWriter printer) {
		boolean requiresInstance = callType != Opcodes.INVOKESTATIC;
		if (requiresInstance) {
			int memberAccessPriority = Precedence.MEMBER_ACCESS.ordinal();
			Expression instanceExpression = argumentExpressions[0];
			int instancePriority = instanceExpression.getPrecedence();
			if (instancePriority > memberAccessPriority) {
				printer.print('(');
			}
			instanceExpression.toString(printer);
			if (instancePriority > memberAccessPriority) {
				printer.print(')');
			}
		} else {
			printer.print(owner.replace('/', '.'));
		}
		printer.print('.');
		printer.print(name);
//		printer.print("  desc:" + desc + "  ");
		printer.print('(');
		for (int i = requiresInstance ? 1 : 0; i < argumentExpressions.length; i++) {
			argumentExpressions[i].toString(printer);
			if ((i + 1) < argumentExpressions.length) {
				printer.print(", ");
			}
		}
		printer.print(')');
	}

	@Override
	public void toCode(MethodVisitor visitor, ControlFlowGraph cfg) {
		Type[] argTypes = Type.getArgumentTypes(desc);
		if (callType != Opcodes.INVOKESTATIC) {
			Type[] bck = argTypes;
			argTypes = new Type[bck.length + 1];
			System.arraycopy(bck, 0, argTypes, 1, bck.length);
			argTypes[0] = Type.getType("L" + owner + ";");
		}
		
		for (int i = 0; i < argumentExpressions.length; i++) {
			argumentExpressions[i].toCode(visitor, cfg);
			if (TypeUtils.isPrimitive(argumentExpressions[i].getType())) {
				int[] cast = TypeUtils.getPrimitiveCastOpcodes(argumentExpressions[i].getType(), argTypes[i]);
				for (int a = 0; a < cast.length; a++) {
					visitor.visitInsn(cast[a]);
				}
			}
		}
		visitor.visitMethodInsn(callType, owner, name, desc, callType == Opcodes.INVOKEINTERFACE);
	}

	@Override
	public boolean canChangeFlow() {
		return false;
	}

	@Override
	public boolean canChangeLogic() {
		return true;
	}

	@Override
	public boolean isAffectedBy(Statement stmt) {
		if(stmt.canChangeLogic()) {
			return true;
		}
		
		for(Expression e : argumentExpressions) {
			if(e.isAffectedBy(stmt)) {
				return true;
			}
		}
		
		return false;
	}

	@Override
	public boolean equivalent(Statement s) {
		if(s instanceof InvocationExpression) {
			InvocationExpression o = (InvocationExpression) s;
			if(callType != o.callType || !name.equals(o.name) || !owner.equals(o.owner) || !desc.equals(o.desc)) {
				return false;
			}
			if(argumentExpressions.length != o.argumentExpressions.length) {
				return false;
			}
			for(int i=0; i < argumentExpressions.length; i++) {
				if(!argumentExpressions[i].equivalent(o.argumentExpressions[i])) {
					return false;
				}
			}
			return true;
		}
		return false;
	}
}