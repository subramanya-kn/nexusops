"""Read-only diagnostic tools. No tool here can mutate infrastructure — by construction.

Every tool reads through an :class:`~app.tools.infra_client.InfraClient`, which exposes only
GET-style reads (status, logs, metrics, health, deploys, dependencies). There is no write
method anywhere in this package.
"""
