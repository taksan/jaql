package com.ibm.jaql.json.schema;

import com.ibm.jaql.json.type.JsonRecord;
import com.ibm.jaql.json.type.JsonType;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.lang.expr.core.Parameters;
import com.ibm.jaql.lang.util.JaqlUtil;
import com.ibm.jaql.util.Bool3;

/** Generic schema used for types that do not have parameters */
public class GenericSchema extends Schema
{
  JsonType type;

  // -- schema parameters -------------------------------------------------------------------------
  
  private static Parameters parameters = null; 
  
  public static Parameters getParameters()
  {
    if (parameters == null)
    {
      parameters = new Parameters(); // no args
    }
    return parameters;
  }
  
  
  // -- construction ------------------------------------------------------------------------------
  
  public GenericSchema(JsonType type, JsonRecord args)
  {
    this(type);
  }
  
  public GenericSchema(JsonType type)
  {
    JaqlUtil.enforceNonNull(type);
    this.type = type;
  }

  
  // -- Schema methods ----------------------------------------------------------------------------

  @Override
  public SchemaType getSchemaType()
  {
    return SchemaType.GENERIC;
  }

  @Override
  public Bool3 isNull()
  {
    return Bool3.FALSE;
  }

  @Override
  public Bool3 isConst()
  {
    return Bool3.UNKNOWN;
  }

  @Override
  public Bool3 isArray()
  {
    return Bool3.FALSE;
  }

  @Override
  public boolean matches(JsonValue value) throws Exception
  {
    return type.clazz.isInstance(value);
  }

  
  // -- getters -----------------------------------------------------------------------------------
  
  public JsonType getType()
  {
    return type;  
  }
}
