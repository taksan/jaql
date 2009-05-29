package com.ibm.jaql.lang.expr.core;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.ibm.jaql.json.type.BufferedJsonRecord;
import com.ibm.jaql.json.type.JsonRecord;
import com.ibm.jaql.json.type.JsonString;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.util.Bool3;

public class ArgumentExpr extends Expr
{
  private JsonString[] names;     // cached (1) evaluate names only once while (2) allowing compile time checks
  private Set<JsonString> namesSet; // for duplicate checking
  private Parameters descriptor;
 
  public ArgumentExpr(Parameters descriptor, Expr[] args)
  {
    super(args);
    this.descriptor = descriptor;
    
    // basic checks for number of arguments
    if (args.length > descriptor.noParameters())
    {
      throw new IllegalArgumentException("too many arguments");
    }
    if (args.length < descriptor.noRequiredParamters())
    {
      throw new IllegalArgumentException("insufficient number of arguments");
    }
    
    // in depth checks (as good as possible at compile time)
    names = new JsonString[args.length];
    namesSet = new HashSet<JsonString>();
    boolean foundNamedArg = false;
    for (int i=0; i<args.length; i++)
    {
      if (args[i] instanceof NameValueBinding)
      {
        foundNamedArg = true;
        NameValueBinding arg = (NameValueBinding)args[i];
        Expr nameExpr = arg.nameExpr();
        if (nameExpr instanceof ConstExpr)
        {
          JsonValue nameValue = ((ConstExpr)nameExpr).value;
          checkArg(i, nameValue); 
          names[i] = (JsonString)nameValue;
        }
        else
        {
          // checks done later, names[i] still null
        }
      }
      else
      {
        if (foundNamedArg)
        {
          throw new IllegalArgumentException("unnamed arguments must not follow named arguments"); 
        }
        names[i] = descriptor.nameOf(i);
        namesSet.add(names[i]);
      }
    }
  }
  
  public ArgumentExpr(Parameters descriptor, ArrayList<Expr> args)
  {
    this(descriptor, args.toArray(new Expr[args.size()]));
  }
  
  public JsonRecord constEval() 
  {
    // check that everything is const
    for (int i=0; i<names.length; i++) 
    {
      if (names[i] == null)
      {
        // this name is not constant
        return null;
      }
      
      Expr value;
      if (exprs[i] instanceof NameValueBinding)
      {
        value = ((NameValueBinding)exprs[i]).valueExpr();
      }
      else
      {
        value = exprs[i];
      }
      
      if (!(value instanceof ConstExpr))
      {
        // this value is not constant
        return null;
      }
    }
    
    // then evaluate
    try
    {
      return eval(null); // context not needed
    } catch (RuntimeException e)
    {
      throw e;
    }
    catch (Exception e)
    {
      throw new UndeclaredThrowableException(e);
    }    
  }
  
  private void checkArg(int i, JsonValue nameValue)
  {
    if (nameValue == null || !(nameValue instanceof JsonString))
    {
      throw new IllegalArgumentException("invalid argument name: " + nameValue);
    }
    JsonString name = (JsonString)nameValue;
    
    Integer position = descriptor.positionOf(name);
    if (position == null)
    {
      throw new IllegalArgumentException("invalid argument name: " + name);
    }
    if (descriptor.isRequired(position))
    {
      JsonString expectedName = descriptor.nameOf(position);
      // positional arguments can be named
      if (!name.equals(expectedName))
      {
        throw new IllegalArgumentException("found argument name " + name + " but expected " 
            + expectedName);
      }
    } 
    if (!namesSet.add(name))
    {
      throw new IllegalArgumentException("duplicate argument: " + name);
    }
    
  }

  public Bool3 evaluatesChildOnce()
  {
    return Bool3.TRUE;
  }
  
  @Override
  public JsonRecord eval(Context context) throws Exception
  {
    BufferedJsonRecord r = new BufferedJsonRecord(); 
    
    // construct the function argument
   for (int i=0; i<exprs.length; i++)
   {
     JsonString name;
     JsonValue value;
     if (exprs[i] instanceof NameValueBinding)
     {
       NameValueBinding namedArg = (NameValueBinding)exprs[i];
       Expr nameExpr = namedArg.nameExpr();
       Expr valueExpr = namedArg.valueExpr();
       
       if (names[i] != null)
       {
         name = names[i];
       }
       else
       {
         JsonValue nameValue = nameExpr.eval(context);
         checkArg(i, nameValue);
         name = (JsonString)nameValue;
       }
       value = valueExpr.eval(context);
     }
     else
     {
       name = names[i]; // never null
       value = exprs[i].eval(context);
     }
     
     if (!descriptor.schemaOf(names[i]).matches(value))
     {
       throw new IllegalArgumentException("illegal value for argument " + names[i]);
     }
     // TODO: check schema!
     r.add(name, value);
   }
   
   return r;    
  }
}
