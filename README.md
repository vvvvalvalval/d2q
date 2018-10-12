# d2q

A simple, efficient, generally applicable engine for implementing graph-pulling API servers in Clojure. 

**Project status: alpha, subject to change.** The core algorithm has been running in production for several months now,
 but I'm still working on improvemnts on the API (names etc.).


## Introduction

**d2q** (_Demand-Driven Querying_) addresses some perceived limitations of various graph-pulling server-side libraries
 (GraphQL libraries, Datomic Pull, Pathom, Walkable etc.)
 in the Clojure ecosystem, especially regarding their performance characteristics (N+1 problem),
 genericity, programmability or simplicity.

It does so by providing by providing an execution engine for a **general, data-oriented query language,**
 which allows for registering data-fetching logic in a way that is declarative,
 expressive, and friendly to performance.

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


## Planned features

* **conditional queries:** execute some subqueries 
* **(mutually) recursive queries:** continue execution by jumping to another point in the query tree (now a graph).
* various **backend auxiliary libraries**, e.g `d2q.backend.jdbc`, `d2q.backend.datomic-peer`, etc.
* **Om Next -> d2q** adapter
* **GraphQL -> d2q** adapter

## Usage

* [Tutorial in examples](./test/d2q/test/example/persons.clj)
* [Examples projects](https://github.com/vvvvalvalval/d2q-examples)

## License

Copyright © 2018 BandSquare and contributors

Distributed under the MIT license.
