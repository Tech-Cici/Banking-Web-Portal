/*
 * A REQUEST'S ENDING IS NOT ALWAYS A COLLECTION.
 *
 * WHAT WAS WRONG. V18 gave a service request collected_at and collected_by_name, paired by
 * a constraint to status = 'COLLECTED'. That was exactly right while every request was a
 * thing somebody walked in and picked up. V30 added GRANTED, because a loan is approved and
 * paid into an account rather than fetched from a counter -- and a granted application
 * records who approved it and when, which is the same fact in the same two columns under a
 * status the constraint did not allow them with.
 *
 * TWO CHOICES WERE AVAILABLE AND ONE WAS WORSE. Adding granted_at and granted_by_name beside
 * the existing pair would leave four columns of which exactly two are ever filled, and every
 * screen asking "who finished this?" would have to ask twice and coalesce. The columns record
 * when a request reached its ending and who ended it; that is one fact, so it stays one pair.
 *
 * SO THEY ARE RENAMED TO SAY WHAT THEY HOLD. A column called collected_at holding the moment
 * a loan was approved is a name that misleads every future reader, and this schema's habit is
 * to say what is true rather than to leave the reader to find out.
 *
 * DECLINED KEEPS ITS OWN COLUMNS, deliberately. A decline carries a reason, which the other
 * endings do not, and its constraint already ties the three together. Folding it in here
 * would mean a nullable reason on a pair that is otherwise always complete.
 */

ALTER TABLE service_requests RENAME COLUMN collected_at TO finished_at;
ALTER TABLE service_requests RENAME COLUMN collected_by_name TO finished_by_name;

ALTER TABLE service_requests DROP CONSTRAINT IF EXISTS service_request_collected_together;

/*
 * BOTH OR NEITHER, AND ONLY ON AN ENDING THAT HAS ONE. A request that is still open, or has
 * been declined, has not been finished by anybody in this sense -- the decline columns record
 * that -- so the pair must be empty.
 */
ALTER TABLE service_requests
    ADD CONSTRAINT service_request_finished_together
        CHECK ((status IN ('COLLECTED', 'GRANTED')
                  AND finished_at IS NOT NULL
                  AND finished_by_name IS NOT NULL)
            OR (status NOT IN ('COLLECTED', 'GRANTED')
                  AND finished_at IS NULL
                  AND finished_by_name IS NULL));

COMMENT ON COLUMN service_requests.finished_at IS
    'When the request reached an ending somebody performed: handed over, or granted. Null while open, and null on a decline, which records its own timestamp and a reason.';

COMMENT ON COLUMN service_requests.finished_by_name IS
    'The member of staff who handed it over or granted it, as their name stood at the time.';
