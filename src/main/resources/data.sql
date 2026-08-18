INSERT INTO kb_knowledge_base (id, name, description, department_id, is_public, created_by)
VALUES
    (1, 'HR Knowledge Base', 'Employee handbook, onboarding, leave policies, and HR procedures.', 'HR', FALSE, 1),
    (2, 'Technical Knowledge Base', 'Architecture notes, development standards, and technical specifications.', 'TECH', FALSE, 2),
    (3, 'Product Knowledge Base', 'Product manuals, feature descriptions, and FAQs.', 'PROD', TRUE, 3),
    (4, 'Company Public Knowledge Base', 'General company information and shared policies.', 'ALL', TRUE, 1)
ON CONFLICT (id) DO NOTHING;

SELECT setval('kb_knowledge_base_id_seq', GREATEST((SELECT MAX(id) FROM kb_knowledge_base), 1));

INSERT INTO kb_permission (kb_id, subject_type, subject_id, permission, granted_by)
VALUES
    (1, 'DEPARTMENT', 'HR', 'WRITE', 1),
    (1, 'DEPARTMENT', 'TECH', 'READ', 1),
    (1, 'DEPARTMENT', 'PROD', 'READ', 1),
    (2, 'DEPARTMENT', 'TECH', 'WRITE', 2),
    (2, 'DEPARTMENT', 'PROD', 'READ', 2)
ON CONFLICT (kb_id, subject_type, subject_id) DO NOTHING;

INSERT INTO kb_eval_dataset (kb_id, question, expected_answer, expected_chunk_ids, created_by)
SELECT 1, 'What should a new employee do on the first day?', 'Collect badge and computer, configure VPN and tools, meet the direct leader, and read the handbook.', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM kb_eval_dataset WHERE kb_id = 1 AND question = 'What should a new employee do on the first day?');

INSERT INTO kb_eval_dataset (kb_id, question, expected_answer, expected_chunk_ids, created_by)
SELECT 1, 'How is annual leave defined?', 'Annual leave depends on years of service and follows the HR policy.', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM kb_eval_dataset WHERE kb_id = 1 AND question = 'How is annual leave defined?');

INSERT INTO kb_eval_dataset (kb_id, question, expected_answer, expected_chunk_ids, created_by)
SELECT 2, 'What is the API rate limiting strategy?', 'Each user is limited by a sliding window strategy, and excess requests return HTTP 429.', NULL, 2
WHERE NOT EXISTS (SELECT 1 FROM kb_eval_dataset WHERE kb_id = 2 AND question = 'What is the API rate limiting strategy?');

INSERT INTO kb_eval_dataset (kb_id, question, expected_answer, expected_chunk_ids, created_by)
SELECT 2, 'What is the commit message convention?', 'Commit messages use type(scope): message, with types such as feat, fix, docs, and refactor.', NULL, 2
WHERE NOT EXISTS (SELECT 1 FROM kb_eval_dataset WHERE kb_id = 2 AND question = 'What is the commit message convention?');

INSERT INTO kb_eval_dataset (kb_id, question, expected_answer, expected_chunk_ids, created_by)
SELECT 3, 'How do I apply for API access?', 'Create an API key in the developer console and choose the proper quota or paid plan.', NULL, 3
WHERE NOT EXISTS (SELECT 1 FROM kb_eval_dataset WHERE kb_id = 3 AND question = 'How do I apply for API access?');
