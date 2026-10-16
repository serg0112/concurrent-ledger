package dev.ledger.domain;

import java.util.List;
import java.util.UUID;

public record JournalTransaction(UUID id, List<Posting> postings) {
    public JournalTransaction {
        // Copy before publication and reject malformed accounting entries at their construction boundary.
        postings = List.copyOf(postings);
        if (postings.size() != 2 || postings.getFirst().amount() == 0
                || Math.addExact(postings.getFirst().amount(), postings.getLast().amount()) != 0) {
            throw new IllegalArgumentException("A journal transaction needs two balanced postings");
        }
    }
}
