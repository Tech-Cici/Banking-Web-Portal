package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.OutboxEntity;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    List<OutboxEntity> findAllByOrderBySentAtDesc(Limit limit);

    List<OutboxEntity> findByRecipientIgnoreCaseOrderBySentAtDesc(String recipient);
}
