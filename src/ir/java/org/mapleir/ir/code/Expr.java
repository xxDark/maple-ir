package org.mapleir.ir.code;

import java.util.Set;

import org.objectweb.asm.Type;

public abstract class Expr extends CodeUnit {

	public enum Precedence {
		NORMAL,
		ARRAY_ACCESS,
		METHOD_INVOCATION,
		MEMBER_ACCESS,
		UNARY_PLUS_MINUS,
		PLUS_MIN_PREPOSTFIX,
		UNARY_LOGICAL_NOT,
		UNARY_BINARY_NOT,
		CAST,
		NEW,
		MUL_DIV_REM,
		ADD_SUB,
		STRING_CONCAT,
		BITSHIFT,
		LE_LT_GE_GT_INSTANCEOF,
		EQ_NE,
		BIT_AND,
		BIT_XOR,
		BIT_OR,
		LOGICAL_AND,
		LOGICAL_OR,
		TERNARY,
		ASSIGNMENT
	}
	
	protected CodeUnit parent;
	
	public Expr(int opcode) {
		super(opcode);
	}
	
	@Override
	public abstract void onChildUpdated(int ptr);
	
	@Override
	public abstract Expr copy();
	
	public abstract Type getType();
	
	public int getPrecedence() {
		return getPrecedence0().ordinal();
	}
	
	protected Precedence getPrecedence0() {
		return Precedence.NORMAL;
	}
	
	public CodeUnit getParent() {
		return parent;
	}
	
	@Override
	public void delete() {
		if(parent != null) {
			parent.deleteAt(parent.indexOf(this));
		} else {
			super.delete();
		}
	}
	
	public void unlink() {
		if(parent != null) {
			parent.deleteAt(parent.indexOf(this));
		}
	}
	
	@Override
	protected void delete0() {
		unlink();
		super.delete0();
	}
	
	protected void setParent(CodeUnit parent) {
		this.parent = parent;
		if(parent != null) {
			setBlock(parent.getBlock());
		} else {
			setBlock(null);
		}
	}
	
	public Stmt getRootParent() {
		CodeUnit p = parent;
		if(p == null) {
			/* expressions must have a parent. */
			throw new UnsupportedOperationException("We've found a dangler, " + id + ". " + this);
		} else {
			if((p.flags & FLAG_STMT) != 0) {
				return (Stmt) p;
			} else {
				return ((Expr) p).getRootParent();
			}
		}
	}
	
	public Iterable<Expr> enumerateWithSelf() {
		Set<Expr> set = _enumerate();
		set.add(this);
		return set;
	}
}