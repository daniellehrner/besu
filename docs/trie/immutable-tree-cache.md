# Immutable tree cache

Bonsai computes state roots on immutable Merkle Patricia trees that stay in memory across blocks.
The account trie and the storage tries of a block are copy-on-write updates of the trees the
previous computation left in the cache, and the new state root is the hash of the updated tree. The
code lives in `org.hyperledger.besu.ethereum.trie.immutabletree` (`ethereum/trie`); Bonsai uses it
through `BonsaiImmutableTrees` (`ethereum/core`).

Without the cache, each block opens its tries from a root hash, loads every node on the updated
paths from the node cache or RocksDB, decodes it, and drops everything once the root is known. The
cache keeps decoded trees, so the next computation starting from the same root finds the paths it
needs already in memory.

## Nodes

| Node | Content |
|---|---|
| `EmptyTreeNode` | the empty trie / an empty branch slot |
| `LeafTreeNode` | remaining key path (ending with the leaf terminator) and value |
| `ExtensionTreeNode` | shared path segment and a child slot |
| `BranchTreeNode` | 16 child slots and an optional value (17th list item) |
| `StoredTreeNode` | a child that is not loaded: location and hash only |

Nodes are immutable. The only mutable state is memoisation (hash, inline encoding, last access
block) and the child slots, which may swap a `StoredTreeNode` for the node it stands for (when a
traversal loads it) and back (when pruning unloads it). Both forms describe the same content, so a
reader racing with such a swap sees a valid tree either way.

Unloaded children are most of a cached tree, so a branch keeps them compactly: a branch decoded
from storage holds the hashes of its hashed children in one 512-byte array and leaves their slots
empty (`null`); the `StoredTreeNode` for such a slot is only created when a traversal asks for it.
Copies of the branch share the array, and pruning unloads a child back into it when the hash
matches (a replaced child becomes a `StoredTreeNode` object instead).

Nodes hold no reference to a loader or a factory. A `TreeSession` carries the loader for one
computation; whoever traverses a tree brings a loader consistent with the root it started from,
which is the contract a `StoredMerklePatriciaTrie` has with its `NodeLoader` too.

### Compatibility with Besu's Patricia tries

Encoding reuses Besu's primitives and layout: `CompactEncoding` paths, the leaf terminator, the
branch value as the 17th item, children embedded when their encoding is shorter than 32 bytes and
referenced by hash otherwise, and the root always hashed. Decoding is delegated to Besu's
`StoredNodeFactory`; hashed children of the decoded node become `StoredTreeNode`s.

Single-key updates are ports of `PutVisitor`, `RemoveVisitor`, `DeferredPutVisitor` and the
`replaceChild` / `replacePath` / `maybeFlatten` rules of Besu's nodes, including their behaviour on
non-canonical input (keys that are prefixes of other keys). Batches of updates are applied in one
parallel pass that produces the canonical trie; it is used when all keys have the same length and
end with the terminator (account and storage tries), and falls back to the single-key rules
otherwise.

`ImmutableTreeParityTest` checks all of this against `StoredMerklePatriciaTrie`: roots after random
operations from empty, with hashed and with prefix keys; batches with and without parallelism;
partial updates of a stored trie, comparing the nodes both write at every reachable location of a
path-based store that validates hashes; decoding round trips; ranges and proofs.

## Opening a root

`ImmutableTreeCache.open(kind, rootHash, session)` returns the registered tree for `(rootHash,
kind)`. If there is none, it loads the root node, and only the root node, through the session's
loader and registers it. Its children are `StoredTreeNode`s until a traversal touches them;
traversals load them into the slot they came from (`swapChild`), so every tree sharing that node
sees it loaded.

`ImmutableTreeMerkleTrie` opens its base root lazily, on its first read or root computation, so a
missing root node fails in the same place as with a classic trie (inside the committer's
`MerkleTrieException` handling, which adds the account for heal).

## Copy on write and commit

An update returns a new root and never modifies its input. Every node on an updated path is
created by the update; every other subtree is shared with the base tree. Any number of roots
(head, new payload, forks) are alive at once, sharing all they have in common.

Each node records the id of the session that created it (0 for nodes decoded from storage). A
session's nodes are exactly the nodes its commit writes: `TreeOps.commit` walks down from the new
root through the nodes the session created only, writes those of 32 bytes or more (and the root),
and never visits, let alone loads, a node it did not create. This is Besu's dirty-only commit
without a mutable dirty flag. It is correct because the base of a persisting computation is the
root the storage holds: every node of the base tree is on disk, whichever tree object stands for
it. After a commit the session is renewed, so a later commit on the same trie writes only newer
nodes.

## Roles, registration and pruning

A persist of a Bonsai world state (or a frozen `rootHash()` recompute) collects the roots its tries
compute. Only when the persist succeeds, after the root was verified against the block header, are
they registered and bound:

| World state | State root bound as | Storage roots bound per account as |
|---|---|---|
| head world state | `HEAD` | `HEAD` |
| frozen (payload validation, block building, simulations, tracing) | `NEW_PAYLOAD` | `NEW_PAYLOAD` |
| rolled layer (not frozen, not head) | nothing: fork | nothing: fork |

A failed persist, including a state root mismatch, registers nothing. Registering a root that is
already registered keeps the existing tree, so after forkchoice the head binds the tree the payload
validation of the same block registered. Tries for frontier (pre-Byzantium) receipt roots never
register their intermediate roots. The BAL committer computes on a parent world state and hands its
roots to the world state that persists the result.

A computation opens its base root as a fork if it is not registered yet. Forks are dropped once
unused for `forkRetentionBlocks` (8) blocks; the least recently used ones beyond `maxUnboundRoots`
are dropped as well. The state roots bound as `HEAD` / `NEW_PAYLOAD` are replaced as the chain
moves; storage roots bound to an account are dropped once unused for the prune window.

Pruning runs on a background thread every `pruneIntervalBlocks` (8) head advances, and whenever
more roots are registered than `maxUnboundRoots`. It drops expired roots, then walks every
registered root and replaces each loaded child that no traversal touched in the last
`pruneAfterBlocks` blocks by a stored one. If more than `maxCachedNodes` loaded nodes and stored
children are left it halves the window until they fit, and past a window of zero it drops the least
recently used roots (never the head or new payload state root). A subtree shared by several roots
is walked once.

Each counted item costs about 200 bytes of heap: touching 50,000 random accounts of a 300,000
account trie cached 94,000 nodes and 146,000 stored children in 47 MiB. The default budget of
2,000,000 is therefore about 400 MiB. Each prune run logs the counts and an estimate of the heap
they use.

Unloading is safe for every root, including those of frozen computations whose nodes were never
written: a traversal that reaches a node through a root carries a loader consistent with that root,
so the node can be loaded again.

## Locking

A traversal (a read, a root computation, a commit) holds the traversal lock of the base root it
works on. Pruning neither drops a locked root nor unloads nodes below it. Nodes shared with
unlocked roots can still be unloaded through those; that only swaps a slot between two
representations of the same node.

## Configuration

| Option | Default | |
|---|---|---|
| `--Xbonsai-immutable-tree-cache-enabled` | `true` | compute Bonsai state roots on the cache |
| `--Xbonsai-immutable-tree-cache-prune-after-blocks` | `512` | unload nodes untouched for this many blocks |
| `--Xbonsai-immutable-tree-cache-max-nodes` | `2000000` | loaded nodes and stored children pruning keeps the cache under (~400 MiB) |

The archive storage format (`X_BONSAI_ARCHIVE`) keeps computing roots on classic tries.

## Verification

- `ImmutableTreeParityTest` (`ethereum/trie`): see above.
- `ImmutableTreeCacheTest` (`ethereum/trie`): copy on write and coexisting roots, lazy opening,
  head / new payload / storage bindings, locking, age and budget pruning, compact children, and
  commits matching `ParallelStoredMerklePatriciaTrie` over consecutive blocks.
- `BonsaiImmutableTreeIntegrationTest` (`ethereum/core`): the same random blocks through a Bonsai
  node with the cache and one without, comparing roots and, through classic tries, the whole
  stored state; payload validation followed by forkchoice, frozen root recomputes, reorgs rolled
  through trie logs, state root mismatches and aggressive pruning.
- The cache is on by default, so every Bonsai test runs on it, including the execution-spec
  blockchain tests (`./gradlew :ethereum:referencetests:referenceTests --tests
  'org.hyperledger.besu.ethereum.vm.executionspec.ExecutionSpecBlockchainTest*'`), which validate
  each block on a frozen world state and then move the head to it through its trie log.

## Logs

`BonsaiImmutableTrees` logs one DEBUG line per registered computation: the role, the new state root
and block, the base root and whether it came from the cache, how many account and storage trie
nodes had to be loaded, and the cache size. `ImmutableTreeCache` logs each prune run at DEBUG.

```
curl -s -X POST -H 'Content-Type: application/json' --data '{"jsonrpc":"2.0","method":"admin_changeLogLevel","params":["DEBUG",["org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiImmutableTrees","org.hyperledger.besu.ethereum.trie.immutabletree.ImmutableTreeCache"]],"id":1}' http://localhost:8545
```

The node must expose the `ADMIN` API on that port (`rpc-http-api=[..., "ADMIN"]`). Set the level
back with `"INFO"`.

On a node following the chain, each block should log a `NEW_PAYLOAD` line (validation) and a
`HEAD` line (forkchoice) with the same state root, the head line with `base cached: true`, and a
prune line every 8 blocks with the loaded node count staying under the budget.
