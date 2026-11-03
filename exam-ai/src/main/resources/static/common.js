/* exam-ai 前端公共脚本：登录态、API 封装、toast */
const API_BASE = location.origin;

const Auth = {
  get token() { return sessionStorage.getItem('ea_token'); },
  get user() {
    try { return JSON.parse(sessionStorage.getItem('ea_user') || 'null'); } catch (e) { return null; }
  },
  save(token, user) {
    sessionStorage.setItem('ea_token', token);
    sessionStorage.setItem('ea_user', JSON.stringify(user));
  },
  clear() {
    sessionStorage.removeItem('ea_token');
    sessionStorage.removeItem('ea_user');
  },
  /** 页面守卫：未登录跳登录页；角色不符跳对应首页 */
  require(roles) {
    const u = Auth.user;
    if (!Auth.token || !u) { location.replace('/index.html'); return null; }
    if (roles && !roles.includes(u.role)) {
      location.replace(u.role === 'STUDENT' ? '/student.html' : '/teacher.html');
      return null;
    }
    return u;
  },
  logout() {
    Auth.clear();
    location.replace('/index.html');
  },
  home(role) { return role === 'STUDENT' ? '/student.html' : '/teacher.html'; }
};

async function api(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (Auth.token) headers['Authorization'] = 'Bearer ' + Auth.token;
  const res = await fetch(API_BASE + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  let data = null;
  if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }
  if (res.status === 401) {
    Auth.clear();
    location.replace('/index.html');
    throw new Error('登录已过期，请重新登录');
  }
  if (!res.ok) {
    const msg = (data && (data.message || data.error)) || text || ('HTTP ' + res.status);
    const err = new Error(msg);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

function toast(msg, kind = '') {
  let box = document.querySelector('.toast');
  if (!box) { box = document.createElement('div'); box.className = 'toast'; document.body.appendChild(box); }
  const el = document.createElement('div');
  el.className = 't ' + kind;
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transform = 'translateY(6px)'; }, 3200);
  setTimeout(() => el.remove(), 3600);
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

const LEVEL_TEXT = { L1: '提示', L2: '警告', L3: '严重' };

function fmtTime(ts) {
  const d = new Date(ts);
  return d.toLocaleTimeString('zh-CN', { hour12: false });
}

function fmtDuration(sec) {
  const m = Math.floor(sec / 60), s = sec % 60;
  return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}
