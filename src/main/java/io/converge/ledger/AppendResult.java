package io.converge.ledger;

public record AppendResult(long seq, boolean inserted, InventoryPosition position) {
}

