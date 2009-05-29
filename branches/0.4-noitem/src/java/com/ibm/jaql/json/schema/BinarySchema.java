package com.ibm.jaql.json.schema;

import com.ibm.jaql.json.type.JsonBinary;
import com.ibm.jaql.json.type.JsonLong;
import com.ibm.jaql.json.type.JsonRecord;
import com.ibm.jaql.json.type.JsonString;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.lang.expr.core.Parameters;
import com.ibm.jaql.util.Bool3;

/** Schema for a binary value */
public class BinarySchema extends Schema 
{
  // -- private variables ------------------------------------------------------------------------- 
  
  private JsonLong minLength;
  private JsonLong maxLength;

  
  // -- schema parameters -------------------------------------------------------------------------
  
  private static Parameters parameters = null; 
  
  public static Parameters getParameters()
  {
    if (parameters == null)
    {
      parameters = new Parameters(
          new JsonString[] { PAR_MIN_LENGTH, PAR_MAX_LENGTH },
          new String[]     { "long(min=0)" , "long(min=0)" },
          new JsonValue[]  { JsonLong.ZERO , null });
    }
    return parameters;
  }

  
  // -- construction ------------------------------------------------------------------------------
  
  public BinarySchema(JsonRecord args)
  {
    this(
        (JsonLong)getParameters().argumentOrDefault(PAR_MIN_LENGTH, args),
        (JsonLong)getParameters().argumentOrDefault(PAR_MAX_LENGTH, args));
  }
  
  public BinarySchema(JsonLong minLength, JsonLong maxLength)
  {
    // check arguments
    if (!SchemaUtil.checkInterval(minLength, maxLength, JsonLong.ZERO, JsonLong.ZERO))
    {
      throw new IllegalArgumentException("invalid range: " + minLength + " " + maxLength);
    }

    // store length
    if (minLength != null || maxLength != null)
    {
      this.minLength = minLength==null ? JsonLong.ZERO : minLength;
      this.maxLength = maxLength;
    }
  }
  
  public BinarySchema()
  {
  }
  
  // -- Schema methods ----------------------------------------------------------------------------
  
  @Override
  public SchemaType getSchemaType()
  {
    return SchemaType.BINARY;
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
    if (!(value instanceof JsonBinary))
    {
      return false;
    }
    JsonBinary b = (JsonBinary)value;
    
    // check length
    if (!(minLength==null || b.getLength()>=minLength.value)) return false;
    if (!(maxLength==null || b.getLength()<=maxLength.value)) return false;

    // everything ok
    return true;
  }
  

  // -- getters -----------------------------------------------------------------------------------
  
  public JsonLong getMinLength()
  {
    return minLength;
  }
  
  public JsonLong getMaxLength()
  {
    return maxLength;
  }

}
