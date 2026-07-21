# Fashion Scraper Argentina — frontend image (docker-install-alternative)
#
# Additive alternative launcher path; does not modify package.json or any
# existing source file.
#
# Build stage: vite build. VITE_API_BASE_URL MUST be a build-time ARG — Vite
# only inlines env vars present at build time, so promoting the ARG to ENV
# BEFORE `npm run build` is required (setting it only as a runtime container
# env var would silently fall back to the dev-time default API base).
FROM node:20-alpine AS build

ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# Runtime stage: serve the static build with nginx + SPA fallback.
FROM nginx:alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
