export const PROVIDER_NAV_SECTIONS = Object.freeze([
  {
    id: 'provider',
    title: 'My Provider',
    items: [
      {
        id: 'dashboard',
        label: 'Dashboard',
        to: '/provider',
        icon: 'dashboard',
        end: true,
      },
      {
        id: 'profile',
        label: 'My Profile',
        to: '/provider/profile',
        icon: 'user',
      },
      {
        id: 'verification',
        label: 'Verification',
        to: '/provider/verification',
        icon: 'shield',
      },
    ],
  },
  {
    id: 'business',
    title: 'My Business',
    items: [
      {
        id: 'listings',
        label: 'My Listings',
        to: '/provider/listings',
        icon: 'listings',
      },
      {
        id: 'bookings',
        label: 'Bookings',
        to: '/provider/bookings',
        icon: 'bookings',
      },
      {
        id: 'availability',
        label: 'Availability',
        to: '/provider/availability',
        icon: 'availability',
      },
      {
        id: 'messages',
        label: 'Messages',
        to: '/provider/messages',
        icon: 'messages',
      },
      {
        id: 'earnings',
        label: 'Earnings & Payouts',
        to: '/provider/earnings',
        icon: 'wallet',
      },
      {
        id: 'reviews',
        label: 'Reviews',
        to: '/provider/reviews',
        icon: 'reviews',
      },
    ],
  },
])

export const PROVIDER_BOTTOM_NAV_ITEMS = Object.freeze([
  {
    id: 'dashboard',
    label: 'Dashboard',
    to: '/provider',
    icon: 'dashboard',
    end: true,
  },
  {
    id: 'listings',
    label: 'Listings',
    to: '/provider/listings',
    icon: 'listings',
  },
  {
    id: 'bookings',
    label: 'Bookings',
    to: '/provider/bookings',
    icon: 'bookings',
  },
  {
    id: 'messages',
    label: 'Messages',
    to: '/provider/messages',
    icon: 'messages',
  },
  {
    id: 'account',
    label: 'Account',
    to: '/provider/profile',
    icon: 'account',
  },
])