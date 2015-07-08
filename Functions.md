# Introduction #

Jaql has been designed to be extensible.  There are several ways to extend Jaql's capability:

  * Define new functions specified using Jaql
  * Define new functions specified using Java (we are working on other language bindings)
  * Customize or define new data accessors using Jaql's [IO](IO.md) package.

We briefly show how new functions can be written in Jaql, then show how you can
plug-in Java code to define new functions, and finally go into more detail on
how to specify aggregate functions.

# Extending Jaql with Jaql Functions #

A simple example of a function definition and its invocation are as follows:

```
  // define a function referenced by variable myNewFn
  myNewFn = fn(a, b) (
    a + b
  );

  // invoke myNewFn
  myNewFn(1,2);

  // result...
  3
```

The function's parameters are any valid JSON value. The function body in this case assumes
that the operator '+' can be applied to both inputs. In general, a function body can be specified using any valid jaql expression. The value produced by the function is the value produced by its body. We use Jaql functions primarily for re-use. However, there are many times when the function is best expressed using a standard programming language.

# Extending Jaql with Java Functions #

Calling Java from Jaql simply requires writing a class with an
`eval()` method that accepts and returns Jaql's
represenation of JSON types.  The following examples illustrate how to
implement the Java functions, define the functions in the Jaql
system, and invoke the functions.

## Split Example ##

### Split returning an entire array ###

Suppose your data consists of many file system paths.  A useful
operation is to split a path according to a delimiter (e.g., "/").
Such functionality is readily available using Java's [String[](http://java.sun.com/j2se/1.5.0/docs/api/java/lang/String.html#split(java.lang.String)) String.split(String d)] method.  In Jaql, the same
functionality can be exposed through a new function:
`split("/home/mystuff/stuff", "/")`.  The following shows
one way to define `split()`:

```
   package com.acme.extensions.fn;

   import com.ibm.jaql.json.type.JsonArray;
   import com.ibm.jaql.json.type.SpilledJsonArray;
   import com.ibm.jaql.json.type.JsonString;

1  public class Split1
   {
2    private SpilledJsonArray result = new SpilledJsonArray();
     private JsonString resultStr = new JsonString();
     
3    public JsonArray eval(JsonString jstr, JsonString jdelim) throws Exception
     {
4      if( jstr == null || jdelim == null )
       {
         return null;
       }
5     String str = jstr.toString();
       String delim = jdelim.toString();
       
6     String[] splits = str.split(delim);

7     result.clear();
       for( String s: splits )
       {
8        resultStr.set(s);
          result.add(resultStr);
       }

9      return result;
     }
   }
```

A Jaql function is implemented by creating a class (1).  The class can
store any local state (2) for the function; however, the jaql compiler
assumes that the function can be called repeatedly with the same
arguments and get the same result (i.e., the function has no
side-effects).  The class has an `eval()` method (3) that
takes `JaqlType` parameters and returns a
`JaqlType` result.  The function should assume that the
parameters might be `null` (4). In this case, a
`null` is simply returned; alternatively, the function
could throw an exception if a non-null value is required.  In many
cases, the `JaqlType` values need to be converted to
another form, e.g., converted from JsonString to a regular Java String
(5).  With the inputs processed, the function performs is task (6).
This function collects all of the substrings into a
`JsonArray` (7) of `JsonString` values (8), and
returns the entire array (9).

### Defining and calling split in Jaql ###

The function name and implementing class are defined with Jaql
using `javaudf()`.  The function can then be
invoked like any other function in Jaql. Please refer to [instructions](Running.md)
for how to run Jaql so that it can find your Java class files.

```
    split1 = javaudf("com.acme.extensions.fn.Split1");
    path = '/home/mystuff/stuff';

    split1(path, "/");
    // [ "", "home", "mystuff", "stuff" ]

    count(split1(path, "/"));
    // 4

    split1(path, "/")[1]; 
    // "home"
```

### Split returning an array via an iterator ###

Functions that return array can either materialize and return an
entire array during `eval()` as above, or
`eval()` may return an `JsonIterator` that returns
one element at a time.  The advantage of using an iterator is that the
entire array need not be stored in memory -- or even computed in many
cases.  The following example is the sample string split function that
returns an iterator:

```
    package com.acme.extensions.fn;

    import com.ibm.jaql.json.util.JsonIterator;
    import com.ibm.jaql.json.type.JsonString;

    public class Split2
    {
1     public JsonIterator eval(JsonString jstr, JsonString jdelim) throws Exception
      {
        if( jstr == null || jdelim == null )
        {
          return null;
        }
        String str = jstr.toString();
        String delim = jdelim.toString();
        
        final String[] splits = str.split(delim);

2       return new JsonIterator() 
        {
3         int i = 0;
          private JsonString resultStr = new JsonString();
          
4         public boolean moveNext()
          {
            if( i >= splits.length )
            {
              return false;
            }
            current = resultStr;
            resultStr.set(splits[i]);
            i++;
            return true;
          }
        };
      }
    }
```

The return type changed to `JsonIterator` from
`JsonString` (1), and the return value produces an anonymous
`JsonIterator` subclass (2).  When returning an iterator, it
is important to be aware that multiple invocations of the function may
be active at the same time.  Therefore, a new iterator (2) is returned
and most of the state is stored inside the iterator (3).
`JsonIterator` is an abstract class that requires a
`moveNext()` method (4) that sets the `current`
value and returns true, or returns false if there is no next value
value.  For the query writer, this implmentation of split behaves
nearly identically to the previous one.  The function definition,
invocation, and result are similar to the above:

```
    split2 = javaudf("com.acme.extensions.fn.Split2");
    path = '/home/mystuff/stuff';

    split2(path, "/");
    // [ "", "home", "mystuff", "stuff"]

    count(split2(path, "/"));
    // 4

    split2(path, "/")[1];
    // "home"
```

# Writing Aggregate Functions #

Jaql includes standard database aggregate functions, like
`sum,count, min, max`, and `avg`.  Jaql supports partial aggregation
> for these functions using "combiners" inside Hadoop's map/reduce framework for greater
parallelism and reduced data shipping.  Syntactically, these functions
look like "holistic" aggregate functions -- an aggregate that requires
all the data before returning an answer.  However, they actually
expand into "algebraic" aggregates using the combine expression.
Median is the typical example of a holistic function:

```
  median = fn(items) (
    sorted = items -> sort by [$],

    sorted[int(count(sorted)/2)]
  );

  median( [ 1, 4, 5, 3, 2 ] ); // 3
```

If you need the exact median, we cannot improve upon this much.  But
consider variance instead.  It can be computed from the sum of the
numbers and the sum of squares.  The `combine` expression
is used to define "algebraic" aggregates -- an aggregate that can be
applied on portions of the data and combined to produce a final
result.  Such aggregates typically have an "initialization phase" that
creates a partial aggregate from a single item, a "combining phase"
where partial aggregates are combined into larger partial aggregates,
and a "final phase" that transforms the largest partial aggregate into
the desired result.

The `combine` expression handles the combining
phase. It takes any two partial aggregates from its input, aggregates
them using the combining expression, and conceptually puts the result
back into the input until the input is reduced to a single item.  The
following example defines variance completely in Jaql using
`combine`:

```
  var = fn(items) (
    init = 
       items
       -> filter not isnull($)
       -> transform { n: 1, s1: $, s2: $*$ },

    combined =
       init 
       -> combine( fn(a,b)
              { n:  a.n  + b.n,
               s1: a.s1 + b.s1,
               s2: a.s2 + b.s2 }),

    E_X  = combined.s1 / combined.n,
    E_X2 = combined.s2 / combined.n,

    E_X2 - E_X * E_X
  );

  var( [ 1, 4, 5, 3, 2 ] ); // 2
```

## Greatest Common Divisor Example ##

The greatest common divisor (gcd) of a set of integers is the largest
positive integer that divides all the numbers without remainder.
Therefore, the gcd is a type of "aggregate" function because, like
sum, it reduces a set of numbers down to a single number.

```
    package com.acme.extensions.fn;

    import com.ibm.jaql.json.util.JsonIterator;
    import com.ibm.jaql.json.type.JsonLong;
    import com.ibm.jaql.json.type.JsonNumber;

    public class GCD1
    {
      private long gcd(long a, long b)
      {
        while( b != 0 )
        {
          long c = b;
          b = a % b;
          a = c;
        }
        return a;  
      }

1     public JsonLong eval(JsonIterator nums) throws Exception
      {
2       if( nums == null )
        {
          return null;
        }
3       if( ! nums.moveNextNonNull() )
        {
          return null;
        }
        JsonNumber n = (JsonNumber)nums.current();
4       long g = n.longValueExact();
        while( nums.moveNextNonNull() )
        {
          n = (JsonNumber)nums.current();
          long x = n.longValueExact();
          g = gcd(g,x);
        }
        return new JsonLong(g);
      }
    }
```

This function is much like the previous examples; a holistic aggregate
function is no different than any other function.  The function deals
with `null` values (2) and empty arrays (3) by returning
`null`.  This example does show one new point:
`JaqlType` values represent JSON values, but a particular
type may have multiple **encodings**.  The JSON number type
is represented internally by `JsonNumber`, but it is abstract with
three subtypes: `JsonDouble`, `JsonLong` and `JsonDecimal`.
The conversion to `long` (4) uses `longValueExact()` to convert any `JsonNumber` to a
long, without loss of precision, or it raises an exception.  The
return type (1) can be either an abstract class or a concrete class,
but parameters should always be the general types.

```
    gcd1 = javaudf("com.acme.extensions.fn.GCD1");

    gcd1(null); // null
    gcd1([]); // null
    gcd1(3); // correctly produces cast error: array expected
    gcd1([3]); // 3
    gcd1([0,0]); // 0
    gcd1([3,0]); // 3
    gcd1([0,3]); // 3
    gcd1([17,13]); // 1
    gcd1([12,18]); // 6
    gcd1([36,18]); // 18
    gcd1([36,18,12]); // 6
    gcd1(range(1000,2000) -> filter mod($,3) == 0 -> transform $ * 31); // 31*3 = 93
```

### Aggregation using 'combine' ###

Holistic aggregate functions suffer from a performance problem: Jaql
can parallelize a holistic aggregate when there are multiple reducers,
but Jaql does not know how to perform partial-aggregation in parallel
using a "combiner" in a map-reduce job.  The next example implements
gcd as a pair-wise function that computes the gcd of two numbers:

```
    package com.acme.extensions.fn;

    import com.ibm.jaql.json.type.JsonLong;
    import com.ibm.jaql.json.type.JsonNumber;


    public class GCD2
    {
      private long gcd(long a, long b)
      {
        while( b != 0 )
        {
          long c = b;
          b = a % b;
          a = c;
        }
        return a;  
      }

      public JsonLong eval(JNumber x, JNumber y)
      {
        long a = x.longValueExact();
        long b = y.longValueExact();
        long g = gcd(a,b);
        return new JsonLong(g);
      }
    }
```

The function is defined and invoked as usual:

```
    gcd2 = javaudf("com.acme.extensions.fn.GCD2");

    gcd2("x","y"); // correctly produces error: numbers expected
    gcd2(17,13); // 1
    gcd2(12,18); // 6
```

We can use the `combine` expression in Jaql to define an
aggregate function that behave like gcd1:

```
    gcd = fn(nums) combine( a, b in nums ) gcd2(a,b);

    gcd(range(1000,2000) -> filter mod($,3) == 0 -> transform $ * 31); // 31*3 = 93
```

The `combine` expression implements the iteration that was
inside of gcd1.  Conceptually, `combine` will take any two
numbers from its input array, evaluate the pairwise combining
expression with those two numbers, place the result back into the
array, and repeat until the array has one item in it.  The promise
made is that the combining expression is commutative
(`gcd2(a,b) == gcd2(b,a)`) and associative
`gcd2(a,gcd2(b,c)) == gcd2(gcd2(a,b), c)`). In other
words, `combine` may call `gcd2` with arbitrary
subsets of numbers, or with results from earlier invocations.

We could have used `gcd1` in a `combine`
expression by making a list out of the two items:

```
    gcd = fn(nums) (nums -> combine( fn(a,b) gcd1( [a,b] ) ));

    gcd(range(1000,2000) -> filter mod($,3) == 0 -> transform $ * 31); // 31*3 = 93
```

When the data lives in Hadoop's HDFS, Jaql considers using map-reduce
to evaluate queries.  The following writes a bunch of records into HDFS:

```
    range(1,100)
    -> expand each i (
         range(1,100)
         -> transform each j { a: i, b: i * j }
       )
    -> write(hdfs('/temp/nums'));
```

The following grouping query uses Hadoop's map-reduce to evaluate the
gcd.  Because gcd1 is a holistic aggregate function (it requires all
of the data before it will produce its result) is run in parallel by
each of the reducer tasks:

```
    gcd1 = javaudf("com.acme.extensions.fn.GCD1");
    gcd = fn(nums) gcd1( nums );

    read(hdfs('/temp/nums'))
    -> group by a = $.a
         into { a, g: gcd($[*].b) };
    // [ {a:1, g:1}, {a:2, g:2}, ..., {a:100, g: 100} ]
```

The next version also uses Hadoop's map-reduce to evaluate the gcd.
Because we are now using a `combine` expression, gcd is run
in parallel by each of the map tasks (using a combiner) to produce
partial aggregates and again by the reduce tasks to produce the final
aggregation:

```
    gcd2 = javaudf("com.acme.extensions.fn.GCD2");
    gcd = fn(nums) ( nums -> combine( fn(a,b) gcd2( a,b ) ) );

    read(hdfs('/temp/nums'))
    -> group by a = $.a
         into { a, g: gcd($[*].b) };
    // [ {a:1, g:1}, {a:2, g:2}, ..., {a:100, g: 100} ]
```

The `explain` statement can be used to see how Jaql will
evaluate a query.  The result is a transformed query that is
equivalent to the original query.  The transformed query typically
uses low-level functions of Jaql and contains many generated
variables.  (The pretty printer isn't in place yet either...)

```
    explain 
    read(hdfs('/temp/nums'))
     -> group by a = $.a
          into { a, g: gcd($[*].b) }

    // Cleaned up result:
    mrAggregate( {
         input: { type: "hdfs", location: "/temp/nums" }, 
         output: HadoopTemp(),
         map: fn(invals) invals -> transform each $ [$.a, $],
         aggregate: fn(key, vals) [ vals[*].b -> combine( fn(a,b) gcd2(a,b) ) ],
         final: fn(key, aggs) [{ a: key, g: aggs[0] }]
     } )
     -> read()
     -> sort by [$.a];
```

The `mrAggregate` function is a Jaql function that runs
map/reduce under the covers, but in a particular way.  It is designed
to run a several algebraic aggregates without making multiple passes
over the group.  Instead of using `map`,
`combine` and `reduce` functions like `mapReduce`
function, `mrAggregate` has `init`,
`aggregate` and `final` functions.

The `init` function, much like `map`,
filters and transforms input records.  It produces a list of pairs of
the grouping key (`$.a`) and a vector (of length one in
this case) of initial partial aggregates for one input item
(`[$.b]`).

The `aggregate` function takes the grouping key
(`key`) and an array or array of initial values (`vals`), and produces a new vector of partial aggregates. In this case, it produces a vector of length one using the
`gcd2` function on the first element of each input vector.

The `final` function takes the grouping key
(`key`), one vector of aggregated values
(`aggs`), and produces the final result. The
`final` function can also filter and transform the
aggregates, so in general it produces zero or more results. In this
case, it produces a list of one record that contains the grouping key
and the gcd for that group.

When `mrAggregate` is run using map/reduce,
`init` is evaluated in the map call, `combine`
is called repeatedly during both the combine call and the reduce call,
and `final` is called during the reduce call.

# `JaqlType` Heirarchy #

The following `JaqlType` classes implement the extended
JSON types in Jaql:

```
    JsonRecord
    JsonArray
    JsonBool
    JsonString
    JsonDouble

    JsonDecimal
    JsonLong
    JsonBinary
    JsonDate
    Function
```

The last five are not standard JSON.  JSON null values are
represented by a Java null.

These classes represent immutable values.  Their subclasses are used
to define various mutable encodings of the abstract types.

The abstract type `JsonNumber` should be usually be used to
handle numeric types, using its accessor methods to plain java values.