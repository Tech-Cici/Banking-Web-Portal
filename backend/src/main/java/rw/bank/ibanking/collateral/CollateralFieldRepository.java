package rw.bank.ibanking.collateral;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The declared-and-read fields of each pledge. */
public interface CollateralFieldRepository extends JpaRepository<CollateralFieldEntity, UUID> {

    List<CollateralFieldEntity> findByCollateralId(UUID collateralId);
}
