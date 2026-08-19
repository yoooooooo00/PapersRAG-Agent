INSERT INTO kb_knowledge_base (id, name, description, department_id, is_public, created_by)
VALUES (1, 'Papers Library', 'Default personal literature collection for PapersRAG-Agent.', 'ALL', TRUE, 3)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    department_id = EXCLUDED.department_id,
    is_public = EXCLUDED.is_public,
    created_by = EXCLUDED.created_by,
    updated_at = NOW(),
    is_deleted = FALSE;

SELECT setval('kb_knowledge_base_id_seq', GREATEST((SELECT MAX(id) FROM kb_knowledge_base), 1));

INSERT INTO kb_permission (kb_id, subject_type, subject_id, permission, granted_by)
VALUES
    (1, 'USER', '1', 'WRITE', 3),
    (1, 'USER', '2', 'WRITE', 3),
    (1, 'USER', '3', 'ADMIN', 3),
    (1, 'DEPARTMENT', 'ALL', 'WRITE', 3),
    (1, 'DEPARTMENT', 'HR', 'WRITE', 3),
    (1, 'DEPARTMENT', 'TECH', 'WRITE', 3)
ON CONFLICT (kb_id, subject_type, subject_id) DO UPDATE SET
    permission = EXCLUDED.permission,
    granted_by = EXCLUDED.granted_by;