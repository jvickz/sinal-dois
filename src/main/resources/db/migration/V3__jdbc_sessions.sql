CREATE TABLE spring_session (
    primary_id char(36) PRIMARY KEY,
    session_id char(36) NOT NULL UNIQUE,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100)
);

CREATE INDEX spring_session_expiry ON spring_session (expiry_time);
CREATE INDEX spring_session_principal ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL REFERENCES spring_session (primary_id) ON DELETE CASCADE,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
