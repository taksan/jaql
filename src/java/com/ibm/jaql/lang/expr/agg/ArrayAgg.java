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
package com.ibm.jaql.lang.expr.agg;

import com.ibm.jaql.json.type.JsonArray;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.json.type.SpilledJsonArray;
import com.ibm.jaql.json.util.JsonIterator;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.core.JaqlFn;

/**
 * 
 */
@JaqlFn(fnName = "array", minArgs = 1, maxArgs = 1)
public final class ArrayAgg extends AlgebraicAggregate
{
  private SpilledJsonArray array = new SpilledJsonArray();
  
  @Override
  public JsonValue eval(Context context) throws Exception
  {
    JsonIterator iter = exprs[0].iter(context);
    initInitial(context);
    
    for (JsonValue arg : iter) 
    {
      addInitial(arg);
    }
    
    return getFinal();
  }

  /**
   * Override to handle nulls.
   */
  @Override
  public void evalInitial(Context context) throws Exception
  {
    JsonValue arg = exprs[0].eval(context);
    addInitial(arg);
  }

  /**
   * @param exprs
   */
  public ArrayAgg(Expr[] exprs)
  {
    super(exprs);
  }

  /**
   * @param expr
   */
  public ArrayAgg(Expr expr)
  {
    super(expr);
  }

  @Override
  public void initInitial(Context context) throws Exception
  {
    array.clear();
  }

  @Override
  public void addInitial(JsonValue value) throws Exception
  {
    array.addCopy(value);
  }

  @Override
  public JsonValue getPartial() throws Exception
  {
    return array;
  }

  @Override
  public void addPartial(JsonValue value) throws Exception
  {
    JsonArray array2 = (JsonArray)value;
    array.addCopyAll(array2.iter());
  }

  @Override
  public JsonValue getFinal() throws Exception
  {
    if( array.isEmpty() )
    {
      return null;
    }
    return getPartial();
  }
}
