package dev.ledger.domain;

import java.util.List;
import java.util.UUID;

public record TransferResult(UUID id, Status status, Transfer transfer, List<Posting> postings) {
    public TransferResult {
        postings = List.copyOf(postings);
    }
}
