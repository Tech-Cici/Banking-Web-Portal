package rw.bank.ibanking.fx;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** The bank's published rates, one row per foreign currency. */
public interface FxRateRepository extends JpaRepository<FxRateEntity, String> {

    List<FxRateEntity> findAllByOrderByCurrencyAsc();

    Optional<FxRateEntity> findByCurrency(String currency);
}
