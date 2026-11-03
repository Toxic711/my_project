-- 演示数据（幂等：每个 INSERT 都用 WHERE NOT EXISTS 守卫，可重复执行）
INSERT INTO probe (name) SELECT 'ok' WHERE NOT EXISTS (SELECT 1 FROM probe);

INSERT INTO users (username, email, status)
SELECT 'alice', 'alice@example.com', 1 WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'alice');
INSERT INTO users (username, email, status)
SELECT 'bob', 'bob@example.com', 1 WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'bob');
INSERT INTO users (username, email, status)
SELECT 'carol', 'carol@example.com', 0 WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'carol');

INSERT INTO orders (user_id, amount, status)
SELECT 1, 199.00, 'PAID' WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id = 1 AND amount = 199.00);
INSERT INTO orders (user_id, amount, status)
SELECT 1, 89.50, 'PAID' WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id = 1 AND amount = 89.50);
INSERT INTO orders (user_id, amount, status)
SELECT 2, 320.00, 'REFUNDED' WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id = 2 AND amount = 320.00);

INSERT INTO products (name, price, stock)
SELECT 'DevFlow Pro 订阅', 99.00, 999 WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'DevFlow Pro 订阅');
INSERT INTO products (name, price, stock)
SELECT 'AI 代码审查包', 499.00, 100 WHERE NOT EXISTS (SELECT 1 FROM products WHERE name = 'AI 代码审查包');
