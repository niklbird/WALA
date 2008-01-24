/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.ssa;

import java.util.Collection;

import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.shrike.Exceptions;

/**
 * @author sfink
 * 
 */
public class SSACheckCastInstruction extends SSAInstruction {

  private final int result;

  private final int val;

  private final TypeReference declaredResultType;

  SSACheckCastInstruction(int result, int val, TypeReference type) {
    super();
    this.result = result;
    this.val = val;
    this.declaredResultType = type;
  }

  @Override
  public SSAInstruction copyForSSA(int[] defs, int[] uses) {
    if (defs != null && defs.length == 0) {
      throw new IllegalArgumentException("(defs != null) and (defs.length == 0)");
    }
    if (uses != null && uses.length == 0) {
      throw new IllegalArgumentException("(uses != null) and (uses.length == 0)");
    }
    return new SSACheckCastInstruction(defs == null ? result : defs[0], uses == null ? val : uses[0], declaredResultType);
  }

  @Override
  public String toString(SymbolTable symbolTable) {
    return getValueString(symbolTable, result) + " = checkcast " + declaredResultType.getName() + " "
        + getValueString(symbolTable, val);
  }

  /**
   * @see com.ibm.wala.ssa.SSAInstruction#visit(IVisitor)
   * @throws IllegalArgumentException
   *             if v is null
   */
  @Override
  public void visit(IVisitor v) {
    if (v == null) {
      throw new IllegalArgumentException("v is null");
    }
    v.visitCheckCast(this);
  }

  /**
   * @see com.ibm.wala.ssa.SSAInstruction#getDef()
   */
  @Override
  public boolean hasDef() {
    return true;
  }

  @Override
  public int getDef() {
    return result;
  }

  @Override
  public int getDef(int i) {
    if (Assertions.verifyAssertions) {
      Assertions._assert(i == 0);
    }
    return result;
  }

  /**
   * @see com.ibm.wala.ssa.SSAInstruction#getNumberOfUses()
   */
  @Override
  public int getNumberOfDefs() {
    return 1;
  }

  @Override
  public int getNumberOfUses() {
    return 1;
  }

  /**
   * @see com.ibm.wala.ssa.SSAInstruction#getUse(int)
   */
  @Override
  public int getUse(int j) {
    if (Assertions.verifyAssertions)
      Assertions._assert(j == 0);
    return val;
  }

  public TypeReference getDeclaredResultType() {
    return declaredResultType;
  }

  public int getResult() {
    return result;
  }

  public int getVal() {
    return val;
  }

  @Override
  public int hashCode() {
    return result * 7529 + val;
  }

  /*
   * @see com.ibm.wala.ssa.Instruction#isPEI()
   */
  @Override
  public boolean isPEI() {
    return true;
  }

  /*
   * @see com.ibm.wala.ssa.Instruction#isFallThrough()
   */
  @Override
  public boolean isFallThrough() {
    return true;
  }

  /*
   * @see com.ibm.wala.ssa.Instruction#getExceptionTypes()
   */
  @Override
  public Collection<TypeReference> getExceptionTypes() {
    return Exceptions.getClassCastException();
  }

  @Override
  public String toString() {
    return super.toString() + " " + declaredResultType;
  }

}
