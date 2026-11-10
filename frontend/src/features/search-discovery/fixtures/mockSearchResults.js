import mayaImg from '../../../assets/providers/maya-clean.png';
import brightnestImg from '../../../assets/providers/brightnest.png';
import neatLocalImg from '../../../assets/providers/neat-local.png';

/**
 * Approved Reference Fixtures for WP04 Search & Discovery.
 * Source of truth: Reference Design (LocalServe Home Cleaning in Austin, TX).
 */
export const MOCK_SEARCH_RESULTS = [
  {
    id: 'prv-001',
    name: "Maya's Home Care",
    businessName: "Maya's Home Care",
    image: mayaImg,
    isVerified: true,
    rating: 4.9,
    reviewCount: 128,
    category: 'Standard home clean',
    serviceType: 'standard',
    description: 'Reliable home cleaning with supplies included. A personal touch, every visit.',
    distanceText: '1.2 miles away',
    responseTimeText: 'Replies within 15 min',
    nextAvailable: 'Today, 2:00 PM',
    pricing: {
      hourlyRate: 45,
      minimumHours: 2,
      currency: '$',
      unit: 'hr',
    },
    inComparison: true,
    location: 'Austin, TX 78701',
  },
  {
    id: 'prv-002',
    name: 'BrightNest Cleaning',
    businessName: 'BrightNest Cleaning',
    image: brightnestImg,
    isVerified: true,
    rating: 4.8,
    reviewCount: 96,
    category: 'Standard home clean',
    serviceType: 'standard',
    description: 'Flexible cleaning for busy households. Standard and deep-clean packages.',
    distanceText: '2.4 miles away',
    responseTimeText: 'Replies within 30 min',
    nextAvailable: 'Tomorrow, 9:00 AM',
    pricing: {
      hourlyRate: 40,
      minimumHours: 2,
      currency: '$',
      unit: 'hr',
    },
    inComparison: true,
    location: 'Austin, TX 78704',
  },
  {
    id: 'prv-003',
    name: 'Neat & Local',
    businessName: 'Neat & Local',
    image: neatLocalImg,
    isVerified: true,
    rating: 4.7,
    reviewCount: 84,
    category: 'Standard home clean',
    serviceType: 'standard',
    description: 'Detail-focused local team for one-time cleans and recurring care.',
    distanceText: '3.1 miles away',
    responseTimeText: 'Replies within 1 hour',
    nextAvailable: 'Today, 4:30 PM',
    pricing: {
      hourlyRate: 50,
      minimumHours: 2,
      currency: '$',
      unit: 'hr',
    },
    inComparison: false,
    location: 'Austin, TX 78702',
  },
];
