# OpenSpec Specifications — fashion-scraper-new

This directory contains the main (base) specifications for all domains in the fashion-scraper-new project. Specifications in this directory are the authoritative source of truth and are updated by `sdd-archive` when changes are complete.

## Directory Structure

```
specs/
├── README.md (this file)
├── scraper/
│   └── spec.md          # Scraper domain: sites, platform detection, page object model
├── api/
│   └── spec.md          # REST API domain: endpoints, responses, filtering, pagination
├── ml-pipeline/
│   └── spec.md          # ML enrichment: scoring, badges, clustering
├── database/
│   └── spec.md          # SQLite schema: products, price history, ML output, sites
└── frontend/
    └── spec.md          # SPA domain: splash panel, dashboard, filters, tendencies panel
```

## How Specs are Updated

1. **During change**: `sdd-spec` creates delta specs in `openspec/changes/{change-name}/specs/{domain}/spec.md`
2. **After change approval & completion**: `sdd-archive` merges delta specs into main specs using RFC 2119 sections:
   - `## ADDED Requirements` → appended to main spec
   - `## MODIFIED Requirements` → replaces matching requirement block
   - `## REMOVED Requirements` → deletes requirement (with migration notes)
   - `## RENAMED Requirements` → renames requirement heading

## Creating Domain Specs

When starting your first change that requires specifications, create domain spec.md files here. Use this template:

```markdown
# {Domain} Specification — fashion-scraper-new

## Overview

Brief description of the domain and its scope.

## Requirements

### Requirement 1: {Title}

**Type**: MUST | SHOULD | MAY  
**Scope**: {Single-line scope}

Given {context}
When {action}
Then {outcome}

### Requirement 2: {Title}

...
```

## Shared Vocabulary

All specs use these terms consistently:

- **Scraper**: Java backend service using Playwright + Page Object Model pattern
- **Platform Type**: Site's e-commerce infrastructure (Shopify, VTEX, TiendaNube, Custom Rails, WooCommerce)
- **Product**: Normalized clothing item with sitio, nombre, precio, url, imagen, categoria, genero, talles, ml_score, marca
- **ML Score**: Enrichment output from Python pipeline (badge, scoreP, ofertaReal)
- **SPA**: Frontend (HTML/CSS/JS vanilla) running at localhost:3000
- **Database**: SQLite with tables: productos, precio_historico, ml_output, sitios_dinamicos

## Versions

These specs track schema version and API contract versions:

- **Schema Version**: MAJOR.MINOR (breaking changes increment MAJOR)
- **API Version**: Tracked per endpoint in API specification

---

Reference: `openspec/config.yaml` for phase rules and artifact store configuration.
