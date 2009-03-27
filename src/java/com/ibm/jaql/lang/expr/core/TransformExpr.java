/*
 * Copyright (C) IBM Corp. 2009.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.ibm.jaql.lang.expr.core;

import java.io.PrintStream;
import java.util.HashSet;

import com.ibm.jaql.json.type.Item;
import com.ibm.jaql.json.util.Iter;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.core.Var;
import com.ibm.jaql.util.Bool3;


public final class TransformExpr extends IterExpr
{
  /**
   * BindingExpr inExpr, Expr projection
   * 
   * @param exprs
   */
  public TransformExpr(Expr[] exprs)
  {
    super(exprs);
  }

  /**
   * @param inBinding
   * @param projection
   */
  public TransformExpr(BindingExpr inBinding, Expr projection)
  {
    super(inBinding, projection);
  }

  /**
   * @param mapVar
   * @param inExpr
   * @param projection
   */
  public TransformExpr(Var mapVar, Expr inExpr, Expr projection)
  {
    super(new BindingExpr(BindingExpr.Type.IN, mapVar, null, inExpr),
        projection);
  }

  /**
   * @return
   */
  public BindingExpr binding()
  {
    return (BindingExpr) exprs[0];
  }

  /**
   * @return
   */
  public Var var()
  {
    return binding().var;
  }

  /**
   * @return
   */
  public Expr projection()
  {
    return exprs[1];
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.Expr#isNull()
   */
  @Override
  public Bool3 isNull()
  {
    return Bool3.FALSE;
  }

  /**
   * 
   */
  @Override
  public Bool3 evaluatesChildOnce(int i)
  {
    if( i == 0 )
    {
      return Bool3.TRUE;
    }
    return Bool3.FALSE;
  }


  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.Expr#decompile(java.io.PrintStream,
   *      java.util.HashSet)
   */
  public void decompile(PrintStream exprText, HashSet<Var> capturedVars)
      throws Exception
  {
    BindingExpr b = binding();
    b.inExpr().decompile(exprText, capturedVars);
    exprText.print("\n-> transform each ");
    exprText.print(b.var.name);
    exprText.print(" ");
    projection().decompile(exprText, capturedVars);
    capturedVars.remove(b.var);
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.IterExpr#iter(com.ibm.jaql.lang.core.Context)
   */
  public Iter iter(final Context context) throws Exception
  {
    final BindingExpr inBinding = binding();
    final Expr proj = projection();
    final Iter inIter = inBinding.inExpr().iter(context);

    return new Iter() {
      public Item next() throws Exception
      {
        Item item = inIter.next();
        if( item == null )
        {
          return null;
        }
        context.setVar(inBinding.var, item);
        item = proj.eval(context);
        return item;
      }
    };
  }

}