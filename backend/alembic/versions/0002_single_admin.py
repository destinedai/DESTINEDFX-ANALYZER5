"""Enforce at most one initial owner administrator."""
from alembic import op
from sqlalchemy import inspect, text

revision = "0002_single_admin"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade():
    # 0001_initial historically uses the live Base metadata. Fresh databases
    # may already have this index, while upgraded databases need it added here.
    names = {item["name"] for item in inspect(op.get_bind()).get_indexes("users")}
    if "uq_users_single_admin" not in names:
        op.create_index(
            "uq_users_single_admin",
            "users",
            ["is_admin"],
            unique=True,
            postgresql_where=text("is_admin IS TRUE"),
            sqlite_where=text("is_admin = 1"),
        )


def downgrade():
    names = {item["name"] for item in inspect(op.get_bind()).get_indexes("users")}
    if "uq_users_single_admin" in names:
        op.drop_index("uq_users_single_admin", table_name="users")
