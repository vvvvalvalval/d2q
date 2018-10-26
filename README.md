# d2q

[![Clojars Project](https://img.shields.io/clojars/v/vvvvalvalval/d2q.svg)](https://clojars.org/vvvvalvalval/d2q)

A simple, efficient, generally applicable engine for implementing graph-pulling API servers in Clojure. 

**Project status: alpha, subject to change.** The core algorithm has been running in production for several months now,
 but I'm still working on improvements on the API (names etc.).


## Introduction

**d2q** (_Demand-Driven Querying_) addresses some perceived limitations of various graph-pulling server-side libraries
 (GraphQL libraries, Datomic Pull, Pathom, Walkable etc.)
 in the Clojure ecosystem, especially regarding their performance characteristics (N+1 problem),
 genericity, programmability or simplicity.

It does so by providing an execution engine for a **general, data-oriented query language,**
 which allows for registering data-fetching logic in a way that is declarative,
 expressive, and friendly to performance.

## Usage

* [Tutorial in examples](./test/d2q/test/example/persons.clj)
* [Examples projects](https://github.com/vvvvalvalval/d2q-examples)

## Features

An **expressive, programmable query language:**
  
* **Similar expressiveness** to GraphQL, Datomic Pull, Om Next (aims to become strictly more expressive in future evolutions,
 so as to become a compilation target for these languages), supporting in particular **parameterized Fields** (`:d2q-fcall-arg`)
 and **aliases** (`:d2q-fcall-key`).
* **Data-oriented,** based on maps to enable **custom application-specific annotations,**
 while providing a keyword/vector shorthand syntax for brevity.
* **Simple:** the information model for queries is minimalist, and not coupled to types,
 data encoding, templating, or writes - you can take care of those aspects separately.
* **Advanced error management:** errors are enriched with relevant query execution data
 (equivalent to stack traces and code location) and don't prevent the server from returning
 a partial result.

A **functional, performance-friendly backend API:**

* **Simple:** schema declaration is independent from data fetching
* **Batching by default:** for data fetching / computation, d2q Resolvers
 process entities and fields in batches (instead of computing one field of one entity,
 e.g 'the first name of user 42', d2q Resolvers compute several fields for several entities, 
 e.g 'the first names, last names and emails of users 42, 56, and 37').
 This **eliminates the N+1 query problem,** and makes it more straightforward to compute 
 derived fields or incorporate nontrivial security policies,
 without being too demanding to the application programmer (fetching a single value from the database
 is very easy, but fetching a table is usually not much harder).
* **Event-driven by default,** based on [Manifold](https://github.com/ztellman/manifold),
 allowing for non-blocking IO and controlled resource utilization.
* a **Functional, data-oriented API:** d2q Resolvers receive and output plain Clojure data;
 thanks to the batching behaviour, no [side-effectful workarounds](https://github.com/facebook/dataloader)
 are required to achieve the performance you deserve.
* If you need even more performance or control, **subquery previews**, **whole-selection processing**
 and **early results substitution** make room for even more optimization.

## Comparison to other libraries

### Lacinia and GraphQL

[Lacinia](https://lacinia.readthedocs.io/en/latest/) provides a batteries-included experience for implementing [GraphQL](https://graphql.org/)
 servers in Clojure. At the time of writing, it's probably the most used library in the Clojure ecosystem for implementing graph-pulling APIs.
 
At the time of writing, Lacinia provides no way of resolving data in batching, and is as such more subject than d2q to the N+1 query problem; 
 it provides [Asynchronous Fields Resolvers](https://lacinia.readthedocs.io/en/latest/resolve/async.html) to mitigate the impact
 on latency, and [Selection previews](https://lacinia.readthedocs.io/en/latest/resolve/selections.html#previewing-selections) 
 to mitigate the impact on both latency and load.
 
[GraphQL](https://graphql.org/) itself, although it has the advantage of being widely adopted, brings its lot of accidental complexity compared
 to data-oriented query languages like d2q. It concerns itself with static typing, writing, syntax, and makes many arbitrary decisions
 on those matters. What's more, its choice of using text as the foundational representation for queries and schema gives it the same
 important limitations as SQL: queries are difficult to compose and transform, which greatly hurts the programmability of GraphQL; 
 you can tell this yields accidental complexity by the presence of features such as [directives](https://graphql.org/learn/queries/#directives) and
 [fragments](https://graphql.org/learn/queries/#fragments), and the fact that GraphQL clients usually assemble queries using templating
 instead of composition.
 
In contrast, d2q queries, because they're just data, are straightforward to assemble, convey and transform; they leave room for your own 
 choices regarding custom annotations and static analysis. Likewise, d2q does not tell you how you should write data, but is easily 
 composed with whatever method you choose for writing.

In time, I can imagine d2q becoming itself a backend execution engine for GraphQL.

### Datomic Pull

The Datomic database natively provides [Pull](https://docs.datomic.com/on-prem/pull.html) as a data-oriented query language for
 pulling trees of data from the database. Because of this, newcomers often think that Datomic Pull is a suitable replacement for something like GraphQL, 
 but Datomic Pull suffers from some important limitations preventing it for accomplishing this mission:
 
* **no parameterized fields**, e.g there's no way to express 'find user whose `:user/id` is 1234' or 'find this user's books sorted by publication name'
* **no derived fields:** the only attributes you can pull are those that exist in the Datomic schema. This hinders an often-necessary separation between
  reads and writes. For instance, your schema may evolve from having a source `:user/full-name` attribute, to it being derived from new source attributes 
  `:user/first-name` and `:user/last-name`. With Datomic Pull, you cannot make clients unaffected by this change. Datomic does support features for 
  derived information in queries (in the form of Datalog rules and database functions) but these cannot be leveraged from Datomic Pull.
* and of course, Datomic Pull supports no other data source than a Datomic database.  

So you can also use d2q as a 'better Datomic Pull'. Having said that, Datomic Pull does have some advantages over d2q:

* Datomic Pull supports wildcarding and recursive queries (this last one is planned for d2q in the future)
* Datomic Pull is probably benefits from performance optimization specifically tailored for Datomic.

You could imagine leveraging Datomic Pull from d2q, using d2q's _early results substitution_.

### Pathom

[Pathom](https://wilkerlucio.github.io/pathom/DevelopersGuide.html) is probably the closest library to d2q in the Clojure ecosystem;
 overall Pathom tends to be more opinionated and less minimalist than d2q, due to it being specifically tailored to Om Next and Fulcro.
 Here are some notable differences:
 
* Pathom uses Om.Next's query syntax, inspired from Datomic Pull, which in my opinion sacrifices some extensibility for the sake of concision,
 e.g by using tuples instead of maps in many places. d2q does not care about concision at all, having the opinion that it should be the job of an upstream
 encoding library.
* Batching and asynchronous resolution exist but are somewhat of an afterthought in Pathom's API; d2q promotes batching and asynchronous resolvers
 as the fundamental building blocks, which makes the API more regular in my opinion. 
* Pathom provides mutations for writing; d2q only cares about reading.
* Pathom implements resolvers as 'deductive rules' for enriching a 'context' represented as a map. This makes Pathom more opinionated,
 and its algorithm less intuitive, than I want d2q to be; in d2q, entities can be represented by anything, and resolvers receive entities 
 in a constant form.
* Like GraphQL, Pathom allows for writing via [mutations](https://wilkerlucio.github.io/pathom/DevelopersGuide.html#_mutations),
 d2q only concerns itself with reading, and being composable with any approach to writing.
* Pathom queries currently have some expressiveness advantages, like Union Queries (there are plans to make d2q at least as expressive, see [Planned Features](#planned-features) below) 
* Pathom currently has more goodies builtin, e.g Pathom Viz, profiling etc.
* Pathom runs on ClojureScript, d2q currently runs only on the JVM.

## Planned features

* **conditional queries:** execute some subqueries based on the value taken by an attribute.
* **(mutually) recursive queries:** continue execution by jumping to another point in the query tree (now a graph).
* various **backend auxiliary libraries**, e.g `d2q.backend.jdbc`, `d2q.backend.datomic-peer`, etc.
* **Om Next -> d2q** adapter
* **GraphQL -> d2q** adapter

## License

Copyright © 2018 BandSquare and contributors

Distributed under the MIT license.
