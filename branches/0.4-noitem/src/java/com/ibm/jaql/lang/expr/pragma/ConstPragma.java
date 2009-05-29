/*
 * Copyright (C) IBM Corp. 2008.
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
package com.ibm.jaql.lang.expr.pragma;

import com.ibm.jaql.json.schema.Schema;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.core.JaqlFn;

/**
 * This is a pragma function to force const evaluation.
 */
@JaqlFn(fnName = "const", minArgs = 1, maxArgs = 1)
public class ConstPragma extends Pragma
{
  protected boolean evaluated = false;
  protected JsonValue value;

  /**
   * @param exprs
   */
  public ConstPragma(Expr[] exprs)
  {
    super(exprs);
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.Expr#isConst()
   */
  @Override
  public boolean isConst()
  {
    return true;
  }
  
  public Schema getSchema()
  {
    return exprs[0].getSchema();
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.Expr#eval(com.ibm.jaql.lang.core.Context)
   */
  public JsonValue eval(Context context) throws Exception
  {
    if( !evaluated )
    {
      value = exprs[0].eval(context);
      evaluated = true;
    }
    return value;
  }
}
