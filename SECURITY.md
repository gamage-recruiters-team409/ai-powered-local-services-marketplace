# Security Guidelines

This repository contains development work for the AI-Powered Local Services Marketplace.

## Never Commit Secrets

Do not commit:

- passwords
- JWT secrets
- MongoDB connection strings
- payment gateway credentials
- AI provider API keys
- email-service credentials
- object-storage credentials
- private tokens
- private keys
- production environment files

Use environment variables for sensitive configuration.

## Private Data

Do not commit:

- real customer personal information
- real provider verification documents
- payout information
- production transaction data
- private company information
- dispute evidence containing personal information
- production database exports

Use safe development or test data only.

## Secret Exposure

If a credential or secret is accidentally committed:

1. Inform the Team Lead immediately.
2. Revoke or rotate the exposed credential.
3. Remove it from active files.
4. Clean Git history if required.
5. Update ignore/configuration rules.
6. Verify that the exposed credential can no longer be used.

Deleting a secret in a later commit is not sufficient.

## Security Requirements

Development must maintain:

- secure authentication
- role-based access control
- ownership validation
- server-side validation
- secure file-upload handling
- least-privilege access
- secure error handling
- secure webhook validation
- audit logging for sensitive operations
- protection of personal information used by external integrations or AI services

