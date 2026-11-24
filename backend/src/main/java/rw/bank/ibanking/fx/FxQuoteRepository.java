package rw.bank.ibanking.fx;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Rates the bank has held for customers. */
public interface FxQuoteRepository extends JpaRepository<FxQuoteEntity, UUID> {}
