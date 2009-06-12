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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.PriorityQueue;

import com.ibm.jaql.io.serialization.binary.BinaryFullSerializer;
import com.ibm.jaql.io.serialization.binary.def.DefaultBinaryFullSerializer;
import com.ibm.jaql.json.type.JsonArray;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.json.util.JsonIterator;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.core.JaqlFunction;
import com.ibm.jaql.lang.util.JaqlUtil;
import com.ibm.jaql.lang.util.JsonHashTable;
import com.ibm.jaql.util.BaseUtil;
import com.ibm.jaql.util.Bool3;
import com.ibm.jaql.util.BufferedRandomAccessFile;
import com.ibm.jaql.util.LongArray;

/**
 * groupCombine(input $X, initialFn, partialFn, finalFn) => $Y
 *    initialFn = fn($k,$X) e1 => $P
 *    partialFn = fn($k,$P) => $P
 *    finalFn = fn($k,$P) => $Y
 */
@JaqlFn(fnName="groupCombine", minArgs=4, maxArgs=4)
public class GroupCombineFn extends IterExpr
{
  public static final long memoryLimit = 32 * 1024 * 1024;  // TODO: make configurable
  public static final long keyLimit    =  1 * 1024 * 1024;  // TODO: make configurable
  protected JsonHashTable initialHT;
  protected JsonHashTable partialHT;
  protected JsonValue[] pair = new JsonValue[2];
  protected JaqlFunction initialFn;
  protected JaqlFunction partialFn;
  protected JaqlFunction finalFn;
  protected File spillFileHandle;
  protected RandomAccessFile spillFile;
  protected LongArray spillOffsets;
  private final BinaryFullSerializer SERIALIZER =DefaultBinaryFullSerializer.getInstance();
  /**
   * @param exprs
   */
  public GroupCombineFn(Expr[] exprs)
  {
    super(exprs);
  }


  public GroupCombineFn(Expr input, Expr initialFn, Expr partialFn, Expr finalFn)
  {
    super(new Expr[]{input, initialFn, partialFn, finalFn});
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

  public Expr input()       { return exprs[0]; }
  public Expr initialExpr() { return exprs[1]; }
  public Expr partialExpr() { return exprs[2]; }
  public Expr finalExpr()   { return exprs[3]; }
  
  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.IterExpr#iter(com.ibm.jaql.lang.core.Context)
   */
  public JsonIterator iter(final Context context) throws Exception
  {
    initialFn = getFunction(context, initialExpr());
    partialFn = getFunction(context, partialExpr());
    finalFn   = getFunction(context, finalExpr());
    
    boolean madePartials = false;
    initialHT = new JsonHashTable(1); // TODO: add comparator support to JsonHashtable
    partialHT = new JsonHashTable(1); // TODO: add comparator support to JsonHashtable
    JsonIterator iter = input().iter(context);
    for (JsonValue value : iter)
    {
      JsonArray pairArr = (JsonArray)JaqlUtil.enforceNonNull(value);
      pairArr.getValues(pair);
      initialHT.add(0, pair[0], pair[1]);
      if( initialHT.getMemoryUsage() >= memoryLimit || initialHT.numKeys() >= keyLimit )
      {
        madePartials = true;
        spillInitial(context);
      }
    }
    
    if( ! madePartials )
    {
      return aggInitial(context);
    }
    
    spillInitial(context);
    if( spillFile == null )
    {
      return aggPartial(context);
    }
    
    spillPartial(context);
    return aggSpill(context);
  }


  private JaqlFunction getFunction(Context context, Expr expr) throws Exception
  {
    JaqlFunction f = JaqlUtil.enforceNonNull((JaqlFunction)expr.eval(context));
    if( f.getNumParameters() != 2 )
    {
      throw new RuntimeException("function must have two parameters: "+f);
    }
    return f;
  }


  protected void spillInitial(Context context) throws Exception
  {
    JsonHashTable.Iterator tempIter = initialHT.iter();
    while( tempIter.next() )
    {
      JsonValue key = tempIter.key();
      JsonIterator iter = initialFn.iter(context, key, tempIter.values(0));
      for (JsonValue value : iter)
      {
        partialHT.add(0, key, value);
        if( partialHT.getMemoryUsage() >= memoryLimit || partialHT.numKeys() >= keyLimit )
        {
          spillPartial(context);
        }
      }
    }
    initialHT.reset();
  }

  protected void spillPartial(Context context) throws Exception
  {
    if( spillFile == null )
    {
      spillFileHandle = context.createTempFile("jaql_group_temp", "dat");
      spillFile = new RandomAccessFile(spillFileHandle, "rw"); // TODO: use Buffered when write supported?
      spillOffsets = new LongArray();
    }
    spillOffsets.add(spillFile.getFilePointer());
    partialHT.write(spillFile, 0);
    partialHT.reset();
  }

  protected JsonIterator aggInitial(final Context context)
  {
    return new JsonIterator()
    {
      JsonIterator inner = JsonIterator.EMPTY;
      JsonHashTable.Iterator tempIter = initialHT.iter();
      
      @Override
      public boolean moveNext() throws Exception
      {
        while( true )
        {
          if (inner.moveNext())
          {
            currentValue = inner.current();
            return true;
          }
          if( ! tempIter.next() )
          {
            return false;
          }
          JsonValue key = tempIter.key();
          inner = 
            finalFn.iter(context, key,
                partialFn.iter(context, key, 
                    initialFn.iter(context, key, tempIter.values(0))));
        }
      }
    };
  }

  protected JsonIterator aggPartial(final Context context)
  {
    return new JsonIterator()
    {
      JsonIterator inner = JsonIterator.EMPTY;
      JsonHashTable.Iterator tempIter = partialHT.iter();
      
      @Override
      public boolean moveNext() throws Exception
      {
        while( true )
        {
          if (inner.moveNext())
          {
            currentValue = inner.current();
            return true;
          }
          if( ! tempIter.next() )
          {
            return false;
          }
          JsonValue key = tempIter.key();
          inner = 
            finalFn.iter(context, key,
                partialFn.iter(context, key, tempIter.values(0)));
        }
      }
    };
  }

  protected JsonIterator aggSpill(final Context context) throws IOException
  {
    return new JsonIterator()
    {
      JsonIterator inner = JsonIterator.EMPTY;
      Merger merger = new Merger(spillFileHandle);
      
      @Override
      public boolean moveNext() throws Exception
      {
        while( true )
        {
          if (inner.moveNext())
          {
            currentValue = inner.current();
            return true;
          }
          
          if (!merger.moveNextKey())
          {
            return false;
          }
          
          inner = finalFn.iter(context, merger.currentKey(), merger);
        }
      }
    };
  }

  protected static class Chunk implements Comparable<Chunk>
  {
    JsonValue key = null;
    long offset;
    int numValues;
    long endOffset;
    
    @Override
    public int compareTo(Chunk o)
    {
      return key.compareTo(o.key);
    }
    
  };
  
  protected class Merger extends JsonIterator
  {
    protected PriorityQueue<Chunk> queue = new PriorityQueue<Chunk>();
    protected JsonValue value = null;
    protected BufferedRandomAccessFile spillFile;
    protected JsonValue currentKey;
    
    public Merger(File file) throws IOException
    {
      spillFile = new BufferedRandomAccessFile(file, "r", 16*1024);
      int n = spillOffsets.size();
      spillOffsets.add(spillFile.length());
      for(int i = 0 ; i < n ; i++)
      {
        Chunk chunk = new Chunk();
        chunk.offset = spillOffsets.get(i); 
        chunk.endOffset = spillOffsets.get(i+1);
        spillFile.seek(chunk.offset);
        advanceChunk(chunk);
      }
    }

    /**
     * Move to the next group.  You must consume the entire group using next() before 
     * calling nextKey().  It simply skips to the next chunk of data, and does not
     * verify that the key changed.
     *  
     * @return
     * @throws Exception
     */
    public boolean moveNextKey() throws Exception
    {    
      if( queue.isEmpty() )
      {
        return false;
      }
      Chunk chunk = queue.peek();
      spillFile.seek(chunk.offset);
      currentKey = chunk.key;
      return true;
    }
    
    public JsonValue currentKey() throws Exception
    {
      return currentKey;
    }

    /**
     * Returns the next value with the same key
     */
    @Override
    public boolean moveNext() throws Exception
    {
      while( true )
      {
        Chunk chunk = queue.peek();
        if( chunk.numValues <= 0 )
        {
          return false;
        }
        currentValue = SERIALIZER.read(spillFile, currentValue);
        if( ! nextChunkWithSameKey() )
        {
          return false;
        }
        return true;
      }
    }


    private boolean nextChunkWithSameKey() throws IOException
    {
      Chunk chunk = queue.remove();
      Chunk chunk2 = queue.peek();
      boolean same = chunk2 != null && JsonValue.equals(chunk.key, chunk2.key); 
      advanceChunk(chunk);
      if( same )
      {
        spillFile.seek(chunk2.offset);
      }
      return same;
    }


    private void advanceChunk(Chunk chunk) throws IOException
    {
      if( spillFile.getFilePointer() < chunk.endOffset )
      {
        chunk.key = SERIALIZER.read(spillFile, chunk.key);
        chunk.numValues = BaseUtil.readVUInt(spillFile);
        assert chunk.numValues > 0;
        chunk.offset = spillFile.getFilePointer();
        queue.add(chunk);
      }
    }
  }

}
