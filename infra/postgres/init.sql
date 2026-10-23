-- One database and one user per service ("database per service").
-- Each service can only access its own database.

CREATE USER inventory_svc WITH PASSWORD 'inventory_svc';
CREATE DATABASE inventory_db OWNER inventory_svc;

CREATE USER order_svc WITH PASSWORD 'order_svc';
CREATE DATABASE orders_db OWNER order_svc;

CREATE USER payment_svc WITH PASSWORD 'payment_svc';
CREATE DATABASE payments_db OWNER payment_svc;
