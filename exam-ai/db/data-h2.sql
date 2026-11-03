-- =====================================================================
-- 智能在线考试系统 · H2 demo 种子数据
-- 账号（密码均为 PBKDF2 哈希，明文见 README）：
--   admin    / admin123    ADMIN
--   teacher1 / teacher123  TEACHER
--   student1 / student123  STUDENT
--   student2 / student123  STUDENT
-- 密码哈希格式：pbkdf2$iterations$salt$hash（由 Pbkdf2PasswordEncoder 生成并自校验）
-- =====================================================================

INSERT INTO sys_user (id, username, password_hash, real_name, role, enabled) VALUES
  (1, 'admin', 'pbkdf2$60000$phBb7kkdZPeGE35AynK9ww$RNNuCn+gp+NLFrloP6rBeUEs6skIddvQQe9JREENzCM', '系统管理员', 'ADMIN', 1),
  (2, 'teacher1', 'pbkdf2$60000$hxJ0Ud6IQKoxZVupLCcUHQ$Tdj4dMHZqYTdm4Zc+IrY768TnPuOMfRPYj7q2W2ItNU', '王老师', 'TEACHER', 1),
  (3, 'student1', 'pbkdf2$60000$5hd6uEM+0LdJkFRLUUer9g$oI6PMH9uk63iHCwWJW9WNqBzMwgD/tL0xCkpGgf2bto', '张同学', 'STUDENT', 1),
  (4, 'student2', 'pbkdf2$60000$5hd6uEM+0LdJkFRLUUer9g$oI6PMH9uk63iHCwWJW9WNqBzMwgD/tL0xCkpGgf2bto', '李同学', 'STUDENT', 1);

INSERT INTO exam (id, title, description, question_count, duration_minutes, total_score, status, created_by) VALUES
  (1, 'Java 基础期末测验', 'Spring Boot 与集合框架基础，3 题', 3, 60, 100, 'PUBLISHED', 2),
  (2, '数据结构期中测验', '排序与栈，2 题', 2, 45, 100, 'PUBLISHED', 2),
  (3, '计算机网络随堂测', 'TCP 与 HTTP，2 题', 2, 30, 100, 'PUBLISHED', 2);

INSERT INTO question (id, exam_id, question_type, stem, knowledge_point, difficulty, score, correct_option, analysis, source) VALUES
  (1, 1, 'SINGLE', '以下关于 Java 中 == 与 equals 的说法，正确的是（ ）', 'Java 基础', 'EASY', 30, 'A',
   '== 比较引用地址（基本类型比较值）；Object.equals 默认也是 ==，String/包装类等重写为内容比较。', 'BANK'),
  (2, 1, 'SINGLE', 'JDK8 中 HashMap 在链表长度达到阈值后，会将链表转换为哪种结构以降低查找复杂度（ ）', '集合框架', 'EASY', 30, 'B',
   '链表长度 >= 8 且容量 >= 64 时树化为红黑树，查找复杂度从 O(n) 降到 O(log n)。', 'BANK'),
  (3, 1, 'MULTIPLE', '以下属于 java.util.concurrent 提供的线程安全集合的是（ ）', '并发编程', 'MEDIUM', 40, 'AC',
   'ConcurrentHashMap、CopyOnWriteArrayList 线程安全；ArrayList、HashMap 非线程安全。', 'BANK'),
  (4, 2, 'SINGLE', '快速排序的平均时间复杂度是（ ）', '排序算法', 'MEDIUM', 50, 'B',
   '平均 O(n log n)；最坏（已有序且固定选端点为 pivot）为 O(n^2)，随机 pivot 可缓解。', 'BANK'),
  (5, 2, 'JUDGE', '栈（Stack）遵循先进先出（FIFO）原则。', '线性结构', 'EASY', 50, 'F',
   '栈是后进先出 LIFO；队列才是 FIFO。', 'BANK'),
  (6, 3, 'SINGLE', 'TCP 三次握手的主要目的是（ ）', '传输层', 'MEDIUM', 50, 'A',
   '三次握手用于同步双方初始序列号（ISN）并确认彼此收发能力；加密由 TLS 负责。', 'BANK'),
  (7, 3, 'JUDGE', 'HTTP 协议本身是无状态协议。', '应用层', 'EASY', 50, 'T',
   'HTTP 无状态，靠 Cookie/Session/Token 维护会话状态。', 'BANK');

INSERT INTO question_option (question_id, option_key, content) VALUES
  (1, 'A', '== 对引用类型比较对象地址，equals 可被重写为内容比较'),
  (1, 'B', '== 与 equals 对引用类型完全等价'),
  (1, 'C', 'equals 是基本类型专用运算符'),
  (1, 'D', '== 可以比较两个对象的内容是否相同'),
  (2, 'A', '跳表'),
  (2, 'B', '红黑树'),
  (2, 'C', 'B+ 树'),
  (2, 'D', '开放寻址数组'),
  (3, 'A', 'ConcurrentHashMap'),
  (3, 'B', 'ArrayList'),
  (3, 'C', 'CopyOnWriteArrayList'),
  (3, 'D', 'HashMap'),
  (4, 'A', 'O(n)'),
  (4, 'B', 'O(n log n)'),
  (4, 'C', 'O(n^2)'),
  (4, 'D', 'O(log n)'),
  (5, 'T', '正确'),
  (5, 'F', '错误'),
  (6, 'A', '同步双方初始序列号并确认彼此收发能力'),
  (6, 'B', '加密传输数据'),
  (6, 'C', '压缩报文提高吞吐'),
  (6, 'D', '分配 IP 地址'),
  (7, 'T', '正确'),
  (7, 'F', '错误');
