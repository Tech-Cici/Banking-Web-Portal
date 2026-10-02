/*
 * SESSIONS THAT SURVIVE A RESTART.
 *
 * WHAT WAS REPORTED: "when I reload a page as a signed-in person it redirects me to the
 * sign-in page". That was accurate, and the session really had gone — HttpSession lived in
 * Tomcat's memory, so every restart of the API signed every customer out at once. During a
 * week of shipping migrations that meant being signed out several times an hour.
 *
 * IT IS NOT ONLY A DEVELOPMENT ANNOYANCE, which is why this is a migration and not a note.
 * In-memory sessions mean:
 *
 *   - every deploy signs out every customer, some of them mid-payment;
 *   - two instances behind a load balancer cannot share a session at all, so the service
 *     can never run on more than one node;
 *   - and the only way to end one specific customer's session is to restart the service
 *     and end everybody's.
 *
 * JDBC RATHER THAN REDIS. The service already owns a PostgreSQL database, a connection
 * pool and a Flyway pipeline; holding sessions in Redis would add a second piece of
 * infrastructure to run, secure, monitor and back up, for a table with two columns of
 * consequence. If session volume ever makes the database the wrong place, the switch is a
 * dependency and a property — nothing in the application code knows where sessions live.
 *
 * THIS SCHEMA IS SPRING SESSION'S, COPIED DELIBERATELY. The library ships the same DDL and
 * can create it on start-up with `spring.session.jdbc.initialize-schema=always`. That is
 * turned off (see application.yml) because this codebase's rule is that Flyway owns the
 * schema and nothing else ever does — the same rule that keeps `ddl-auto: none` in every
 * environment, tests included. A schema created by a library on start-up is a schema that
 * differs between environments and has no version anybody can name.
 *
 * KEEP IT IN STEP WITH THE LIBRARY. If spring-session-jdbc is upgraded, compare this file
 * against `org/springframework/session/jdbc/schema-postgresql.sql` in the new jar. A schema
 * the library has outgrown fails at run time, on a customer's sign-in.
 *
 * ONE DIALECT DIFFERENCE, and it is the reason to read this twice. The library's PostgreSQL
 * schema declares ATTRIBUTE_BYTES as BYTEA and its H2 schema declares it LONGVARBINARY.
 * BYTEA is written here because H2 accepts it in PostgreSQL compatibility mode, which is
 * how the test datasource is configured (`MODE=PostgreSQL`) — and that was verified by
 * running the suite rather than assumed. V4 is the standing reminder that the same file is
 * not the same SQL dialect.
 *
 * NOT NAMED IN LOWER CASE like every other table here. The library's queries are written
 * against these exact identifiers; renaming them to match house style breaks every one.
 */

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);

/*
 * Expiry is swept on a schedule, so this index is what stops that sweep scanning every
 * live session each time it runs.
 */
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK
        PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID)
            ON DELETE CASCADE
);

COMMENT ON TABLE SPRING_SESSION IS
    'Spring Session JDBC. Customer and staff sessions live here rather than in Tomcat memory, so a restart or a deploy does not sign everybody out and more than one instance can serve the same session. Schema owned by Flyway (V21), not created by the library on start-up.';

/*
 * WHAT IS IN ATTRIBUTE_BYTES: the serialized security context, which names the customer.
 * It is NOT a credential — no password, hash or device token is in a session — but a dump
 * of this table is a list of who is signed in, and a session id out of it is usable until
 * it expires. It deserves the same handling as the rest of this database and no more.
 */
COMMENT ON COLUMN SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES IS
    'Serialized session attributes, including the security context. No password or device token is ever stored in a session, but a session id taken from here is usable until it expires.';
