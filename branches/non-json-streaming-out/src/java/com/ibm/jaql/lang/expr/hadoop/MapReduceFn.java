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
package com.ibm.jaql.lang.expr.hadoop;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Iterator;

import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.ReflectionUtils;

import com.ibm.jaql.io.hadoop.JsonHolder;
import com.ibm.jaql.io.hadoop.JsonHolderMapOutputKey;
import com.ibm.jaql.io.hadoop.JsonHolderMapOutputValue;
import com.ibm.jaql.json.schema.Schema;
import com.ibm.jaql.json.schema.SchemaFactory;
import com.ibm.jaql.json.type.BufferedJsonArray;
import com.ibm.jaql.json.type.JsonArray;
import com.ibm.jaql.json.type.JsonLong;
import com.ibm.jaql.json.type.JsonRecord;
import com.ibm.jaql.json.type.JsonString;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.json.type.MutableJsonLong;
import com.ibm.jaql.json.type.SpilledJsonArray;
import com.ibm.jaql.json.util.JsonIterator;
import com.ibm.jaql.json.util.UnwrapFromHolderIterator;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.core.JaqlFunction;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.core.JaqlFn;
import com.ibm.jaql.lang.util.JaqlUtil;

/**
 * 
 */
@JaqlFn(fnName = "mapReduce", minArgs = 1, maxArgs = 1)
public class MapReduceFn extends MapReduceBaseExpr
{

  /**
   * mapReduce( record args ) { input, output, map, reduce }
   * 
   * @param exprs
   */
  public MapReduceFn(Expr[] exprs)
  {
    super(exprs);
  }

  /**
   * @param args
   */
  public MapReduceFn(Expr args)
  {
    this(new Expr[]{args});
  }

  @Override
  public Schema getSchema()
  {
    Schema in = exprs[0].getSchema();
    Schema out = in.element(new JsonString("output"));
    return out != null ? out : SchemaFactory.anySchema();
  }
  
  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.Expr#eval(com.ibm.jaql.lang.core.Context)
   */
  public JsonValue eval(final Context context) throws Exception
  {
    JsonRecord args = baseSetup(context);
    JsonValue map = JaqlUtil.enforceNonNull(args.getRequired(new JsonString("map")));
    JsonValue combine = args.get(new JsonString("combine"), null);
    JsonValue reduce = args.get(new JsonString("reduce"), null);

    //conf.setMapperClass(MapEval.class);
    conf.setMapRunnerClass(MapEval.class);
    // use default: conf.setNumMapTasks(10); // TODO: need a way to specify options

    if (combine != null)
    {
      if (reduce == null)
      {
        throw new RuntimeException(
            "reduce function required when combine function is specified");
      }
      conf.setCombinerClass(CombineEval.class);
    }

    if (reduce != null)
    {
      // conf.setNumReduceTasks(2); // TODO: get from options
      conf.setReducerClass(ReduceEval.class);
      JaqlFunction reduceFn = (JaqlFunction) reduce;
      prepareFunction("reduce", numInputs + 1, reduceFn, 0);

      // setup serialization (and propagate schema information, if present)
      setupSerialization(true);
      JsonValue schema = args.get(new JsonString("schema"));
      if (schema != null) {
        conf.set(SCHEMA_NAME, schema.toString());
      }      
    }
    else
    {
      conf.setNumReduceTasks(0);
      
      // setup serialization
      setupSerialization(false);
    }

    if (numInputs == 1)
    {
      JaqlFunction mapFn = (JaqlFunction) map;
      prepareFunction("map", 1, mapFn, 0);
      if (combine != null)
      {
        JaqlFunction combineFn = (JaqlFunction) combine;
        prepareFunction("combine", 2, combineFn, 0);
      }
    }
    else
    {
      JsonArray mapArray = (JsonArray) map;
      JsonIterator iter = mapArray.iter();
      for (int i = 0; i < numInputs; i++)
      {
        if (!iter.moveNext()) {
          throw new IllegalStateException();
        }
        JaqlFunction mapFn = (JaqlFunction) JaqlUtil.enforceNonNull(iter.current());
        prepareFunction("map", 1, mapFn, i);
      }
      if (combine != null)
      {
        JsonArray combineArray = (JsonArray) combine;
        iter = combineArray.iter();
        for (int i = 0; i < numInputs; i++)
        {
          if (!iter.moveNext()) {
            throw new IllegalStateException();
          }
          JaqlFunction combineFn = (JaqlFunction) JaqlUtil.enforceNonNull(iter.current());
          prepareFunction("combine", 2, combineFn, i);
        }
      }
    }

    // Uncomment to run locally in a single process
    // conf.set("mapred.job.tracker", (Object)"local");

    JobClient.runJob(conf);

    return outArgs;
  }

  /**
   * 
   */
  public static abstract class CombineReduceEval extends RemoteEval
  {
    protected SpilledJsonArray[] valArrays;

    /*
     * (non-Javadoc)
     * 
     * @see com.ibm.jaql.lang.expr.hadoop.MapReduceBaseExpr.RemoteEval#configure(org.apache.hadoop.mapred.JobConf)
     */
    public void configure(JobConf job)
    {
      super.configure(job);
      valArrays = new SpilledJsonArray[numInputs];
      for (int i = 0; i < numInputs; i++)
      {
        valArrays[i] = new SpilledJsonArray();
      }
    }

    /**
     * @param values
     * @throws IOException
     */
    protected void splitValues(Iterator<? extends JsonHolder> values) throws IOException
    {
      try {
        // TODO: Would like values to be something that I can open an iterator on. 
        // Until I do the analysis that says that we are going over the values just once,
        // we need to copy the values...
        // TODO: need to reduce copying, big time!
        for (int i = 0; i < numInputs; i++)
        {
          valArrays[i].clear();
        }
        while (values.hasNext())
        {
          JsonValue value = values.next().value;
          int i = 0;
          if (numInputs > 1)
          {
            JsonArray valRec = (JsonArray) value;
            JsonLong id = (JsonLong) JaqlUtil.enforceNonNull(valRec.get(0));
            i = (int) id.get();
            value = valRec.get(1);
          }
          valArrays[i].addCopy(value);
        }
        for (int i = 0; i < numInputs; i++)
        {
        valArrays[i].freeze();
        }
      } 
      catch (IOException e)
      {
        throw e;
      }
      catch (Exception e)
      {
        throw new RuntimeException(e);
      }      
    }
  }

  /**
   * 
   */
  public static class CombineEval extends CombineReduceEval
  implements Reducer<JsonHolder, JsonHolder, JsonHolder, JsonHolder>
  {
    protected JaqlFunction[] combineFns;
    protected JsonValue[]    fnArgs = new JsonValue[2];
    protected MutableJsonLong       outId;
    protected BufferedJsonArray outPair;
    protected JsonHolder valueHolder;
    
    /*
     * (non-Javadoc)
     * 
     * @see com.ibm.jaql.lang.expr.hadoop.MapReduceFn.CombineReduceEval#configure(org.apache.hadoop.mapred.JobConf)
     */
    public void configure(JobConf job)
    {
      super.configure(job);
      combineFns = new JaqlFunction[numInputs];
      for (int i = 0; i < numInputs; i++)
      {
        combineFns[i] = compile(job, "combine", i);
      }
      if (numInputs > 1)
      {
        // FIXME: ideally we could know which input was used to when reading map/combine output files without encoding it on every record
        outId = new MutableJsonLong();
        outPair = new BufferedJsonArray(2);
        outPair.set(0, outId);
      }
      valueHolder = (JsonHolder)ReflectionUtils.newInstance(job.getMapOutputValueClass(), job);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.hadoop.mapred.Reducer#reduce(java.lang.Object,
     *      java.util.Iterator, org.apache.hadoop.mapred.OutputCollector,
     *      org.apache.hadoop.mapred.Reporter)
     */
    public void reduce(JsonHolder key, Iterator<JsonHolder> values,
        OutputCollector<JsonHolder, JsonHolder> output, Reporter reporter)
        throws IOException
    {
      try
      {
        if (numInputs == 1)
        {
          JsonIterator iter = combineFns[0].iter(context, key.value, new UnwrapFromHolderIterator(values));
          for (JsonValue value : iter)
          {
            valueHolder.value = value;
            output.collect(key, valueHolder);
          }
        }
        else
        {
          fnArgs[0] = key.value;
          splitValues(values);
          valueHolder.value = outPair;
          for (int i = 0; i < numInputs; i++)
          {
            fnArgs[1] = valArrays[i];
            JsonIterator iter = combineFns[i].iter(context, fnArgs);
            for (JsonValue value : iter) 
            {
              outId.set(i);
              outPair.set(1, value);
              output.collect(key, valueHolder);
            }
          }
        }
      }
      catch (IOException ex)
      {
        throw ex;
      }
      catch (Exception ex)
      {
        throw new UndeclaredThrowableException(ex);
      }
    }
  }

  /**
   * 
   */
  public static class ReduceEval extends CombineReduceEval
      implements
        Reducer<JsonHolderMapOutputKey, JsonHolderMapOutputValue, JsonHolder, JsonHolder>
  {
    protected JaqlFunction reduceFn;
    protected JsonValue[]  fnArgs;
    JsonHolder keyHolder; // set in configure
    JsonHolder valueHolder;

    /*
     * (non-Javadoc)
     * 
     * @see com.ibm.jaql.lang.expr.hadoop.MapReduceFn.CombineReduceEval#configure(org.apache.hadoop.mapred.JobConf)
     */
    public void configure(JobConf job)
    {
      super.configure(job);
      reduceFn = compile(job, "reduce", 0);
      fnArgs = new JsonValue[numInputs + 1];
      for (int i = 0; i < numInputs; i++)
      {
        fnArgs[i + 1] = valArrays[i];
      }
      
      keyHolder = (JsonHolder)ReflectionUtils.newInstance(job.getOutputKeyClass(), job);
      valueHolder = (JsonHolder)ReflectionUtils.newInstance(job.getOutputValueClass(), job);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.hadoop.mapred.Reducer#reduce(java.lang.Object,
     *      java.util.Iterator, org.apache.hadoop.mapred.OutputCollector,
     *      org.apache.hadoop.mapred.Reporter)
     */
    public void reduce(JsonHolderMapOutputKey key, Iterator<JsonHolderMapOutputValue> values,
        OutputCollector<JsonHolder, JsonHolder> output, Reporter reporter)
        throws IOException
    {
      try
      {
        JsonIterator iter;
        if (numInputs == 1)
        {
          iter = reduceFn.iter(context, key.value, new UnwrapFromHolderIterator(values));
        }
        else
        {
          splitValues(values);
          fnArgs[0] = key.value;
          iter = reduceFn.iter(context, fnArgs);
        }
        keyHolder.value = key.value; // necessary (key has wrong JsonHolder impl)
        for (JsonValue value : iter)
        {
          valueHolder.value = value;
          output.collect(keyHolder, valueHolder);
        }
      }
      catch (IOException ex)
      {
        throw ex;
      }
      catch (Exception ex)
      {
        throw new UndeclaredThrowableException(ex);
      }
    }
  }
}