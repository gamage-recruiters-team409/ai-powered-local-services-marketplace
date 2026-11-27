export const ROLES = {
  CUSTOMER: 'CUSTOMER',
  PROVIDER: 'PROVIDER',
  ADMIN: 'ADMIN',
  SUPER_ADMIN: 'SUPER_ADMIN',
  SUPPORT_ADMIN: 'SUPPORT_ADMIN',
  MODERATOR: 'MODERATOR',
  FINANCE_ADMIN: 'FINANCE_ADMIN',
};

// Every admin area role: these all land on /admin after login (then role selection routes them to their area).
export const ADMIN_ROLES = [
  ROLES.ADMIN,
  ROLES.SUPER_ADMIN,
  ROLES.SUPPORT_ADMIN,
  ROLES.MODERATOR,
  ROLES.FINANCE_ADMIN,
];

export const ALL_ROLES = Object.values(ROLES);

export default ROLES;
