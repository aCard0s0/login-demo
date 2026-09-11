export const $ = (id) => document.getElementById(id);

const TOKEN = 'token';
const NAME = 'name';

export const token = () => sessionStorage.getItem(TOKEN);
export const setSession = (value, name) => {
  sessionStorage.setItem(TOKEN, value);
  sessionStorage.setItem(NAME, name);
};

export const logout = () => {
  // A signed token cannot be recalled, so logging out is this browser forgetting it. It expires on its own.
  sessionStorage.clear();
  location.href = '/';
};

export const api = async (path, options = {}) => {
  const held = token();
  const res = await fetch(path, {
    ...options,
    headers: { 'content-type': 'application/json', ...(held && { authorization: `Bearer ${held}` }), ...options.headers },
  });
  // A 401 while holding a token means it expired; a 401 without one is just a failed login.
  if (res.status === 401 && held) {
    sessionStorage.clear();
    location.href = '/login?expired';
    throw new Error('session expired');
  }
  // Handlers returning void (DELETE) send 200 with an empty body, so parse only when there is one.
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(body?.error || res.statusText);
  return body;
};

/** The private pages are an empty shell without a token, so bounce instead of rendering one. */
export const requireLogin = () => {
  if (token()) return true;
  location.href = '/login';
  return false;
};

/** One header built in one place, so four pages cannot drift apart. */
export const renderNav = () => {
  const header = document.querySelector('header');
  if (!header) return;

  const link = (href, text) => Object.assign(document.createElement('a'), { href, textContent: text });
  const brand = link('/', 'login-demo');
  brand.className = 'brand';

  const nav = document.createElement('nav');
  if (token()) {
    const who = document.createElement('span');
    who.className = 'who';
    // textContent, not innerHTML: the name is whatever the account owner typed.
    who.textContent = sessionStorage.getItem(NAME) ?? '';
    const out = Object.assign(document.createElement('button'), { textContent: 'Log out', className: 'link' });
    out.onclick = logout;
    nav.append(who, link('/todos', 'Todos'), link('/account', 'Account'), out);
  } else {
    nav.append(link('/', 'Home'), link('/login', 'Log in'));
  }
  for (const a of nav.querySelectorAll('a')) {
    if (a.pathname === location.pathname) a.setAttribute('aria-current', 'page');
  }
  header.replaceChildren(brand, nav);
};

renderNav();
