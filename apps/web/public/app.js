export const $ = (id) => document.getElementById(id);

const TOKEN = 'token';
const NAME = 'name';

export const token = () => sessionStorage.getItem(TOKEN);
export const setSession = (value, name) => {
  sessionStorage.setItem(TOKEN, value);
  sessionStorage.setItem(NAME, name);
};

/**
 * What the token says about its holder: id, role and the rest. Read, not verified -- the browser has no way to
 * and no need to. It only decides what to show; every service checks the signature before it acts on any of it.
 */
export const claims = () => {
  try {
    return JSON.parse(atob(token().split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return {};
  }
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
  // A body that is not JSON (a whitelabel error page) is still an error, not a SyntaxError for the user.
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { if (res.ok) throw new Error('unexpected response'); }
  if (!res.ok) throw new Error(body?.error || res.statusText || `HTTP ${res.status}`);
  return body;
};

/** The private pages are an empty shell without a token, so bounce instead of rendering one. */
export const requireLogin = () => {
  if (token()) return true;
  location.href = '/login';
  return false;
};

// Inline SVG, so icons cost no request and no dependency. Line icons from Lucide (ISC), logos are the brands' own.
const ICONS = {
  home: '<path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"/><path d="M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  todos: '<rect x="3" y="5" width="6" height="6" rx="1"/><path d="m3 17 2 2 4-4"/><path d="M13 6h8"/><path d="M13 12h8"/><path d="M13 18h8"/>',
  account: '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
  login: '<path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><path d="m10 17 5-5-5-5"/><path d="M15 12H3"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>',
  agents: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
  bank: '<rect width="20" height="12" x="2" y="6" rx="2"/><circle cx="12" cy="12" r="2"/><path d="M6 12h.01M18 12h.01"/>',
  run: '<polygon points="6 3 20 12 6 21 6 3"/>',
  remove: '<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>',
  add: '<path d="M5 12h14"/><path d="M12 5v14"/>',
  admin: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
  suspend: '<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>',
  reactivate: '<path d="M20 6 9 17l-5-5"/>',
  signup: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M19 8v6"/><path d="M22 11h-6"/>',
  google: '<g stroke="none"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84A11 11 0 0 0 12 23z"/><path fill="#FBBC05" d="M5.84 14.09A6.6 6.6 0 0 1 5.49 12c0-.73.13-1.43.35-2.09V7.07H2.18A11 11 0 0 0 1 12c0 1.78.43 3.45 1.18 4.93z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15A10.6 10.6 0 0 0 12 1 11 11 0 0 0 2.18 7.07l3.66 2.84C6.71 7.31 9.14 5.38 12 5.38z"/></g>',
  github: '<path stroke="none" fill="currentColor" d="M12 .3a12 12 0 0 0-3.8 23.38c.6.12.82-.26.82-.58v-2.03c-3.34.73-4.04-1.6-4.04-1.6-.55-1.4-1.34-1.77-1.34-1.77-1.08-.74.09-.73.09-.73 1.2.09 1.84 1.24 1.84 1.24 1.07 1.83 2.8 1.3 3.49 1 .1-.78.42-1.3.76-1.6-2.67-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.14-.3-.54-1.52.1-3.18 0 0 1-.32 3.3 1.23a11.5 11.5 0 0 1 6 0c2.28-1.55 3.29-1.23 3.29-1.23.64 1.66.24 2.88.12 3.18a4.65 4.65 0 0 1 1.23 3.22c0 4.61-2.8 5.63-5.48 5.92.42.36.81 1.1.81 2.22v3.29c0 .32.21.7.82.58A12 12 0 0 0 12 .3"/>',
};

/** An icon as an element, ready to prepend to a link or button. Unknown names give nothing rather than throw. */
export const icon = (name) => {
  if (!ICONS[name]) return '';
  const span = document.createElement('span');
  span.className = 'icon';
  span.setAttribute('aria-hidden', 'true');
  // innerHTML is safe here: only the constant strings above ever reach it.
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ICONS[name]}</svg>`;
  return span;
};

/** One header built in one place, so four pages cannot drift apart. */
export const renderNav = () => {
  const header = document.querySelector('header');
  if (!header) return;

  const link = (href, text, name) => {
    const a = Object.assign(document.createElement('a'), { href, textContent: text });
    if (name) a.prepend(icon(name));
    return a;
  };
  const brand = link('/', 'login-demo');
  brand.className = 'brand';

  const nav = document.createElement('nav');
  if (token()) {
    const who = document.createElement('span');
    who.className = 'who';
    // textContent, not innerHTML: the name is whatever the account owner typed.
    who.textContent = sessionStorage.getItem(NAME) ?? '';
    const out = Object.assign(document.createElement('button'), { textContent: 'Log out', className: 'link' });
    out.prepend(icon('logout'));
    out.onclick = logout;
    nav.append(who, link('/todos', 'Todos', 'todos'), link('/agents', 'Agents', 'agents'), link('/bank', 'Bank', 'bank'), link('/account', 'Account', 'account'));
    if (claims().role === 'ADMIN') nav.append(link('/admin', 'Admin', 'admin'));
    nav.append(out);
  } else {
    nav.append(link('/', 'Home', 'home'), link('/login', 'Log in', 'login'));
  }
  for (const a of nav.querySelectorAll('a')) {
    if (a.pathname === location.pathname) a.setAttribute('aria-current', 'page');
  }
  header.replaceChildren(brand, nav);
};

renderNav();
