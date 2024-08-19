DO
$$
BEGIN
    -- Check if the table scheduled_tasks exists, and if not, create it
    IF
NOT EXISTS (
        SELECT FROM information_schema.tables 
        WHERE table_schema = 'public' 
        AND table_name = 'scheduled_tasks'
    ) THEN
CREATE TABLE scheduled_tasks
(
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN                  NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT                   NOT NULL,
    PRIMARY KEY (task_name, task_instance)
);
END IF;

    -- Check if the execution_time_idx index exists, and if not, create it
    IF
NOT EXISTS (
        SELECT FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = 'execution_time_idx'
        AND n.nspname = 'public'
    ) THEN
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
END IF;

    -- Check if the last_heartbeat_idx index exists, and if not, create it
    IF
NOT EXISTS (
        SELECT FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = 'last_heartbeat_idx'
        AND n.nspname = 'public'
    ) THEN
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
END IF;

END $$;
