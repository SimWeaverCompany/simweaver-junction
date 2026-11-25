# Security Policy

## Overview

SimWeaver Junction is an open-source software component for bridging and connecting simulation systems and High Level Architecture (HLA) federations.

We take the security of the project and its users seriously. This document describes how to report security vulnerabilities and how such reports are handled.

## Supported Versions

Security fixes are provided for the latest stable release of SimWeaver Junction.

| Version | Supported |
| --- | --- |
| Latest stable release | Yes |
| Previous releases | Best effort |
| Development builds | Best effort |

Users are encouraged to upgrade to the latest stable release to receive security fixes and improvements.

## Reporting a Vulnerability

If you believe you have discovered a security vulnerability in SimWeaver Junction, please report it responsibly.

**Please do not disclose security vulnerabilities through public GitHub issues, discussions, or pull requests.**

### Preferred Reporting Method

Please report security vulnerabilities by email:

**[hello@simweaver.com](mailto:hello@simweaver.com)**

Please include “Security Vulnerability” in the subject line.

### Information to Include

When reporting a vulnerability, please include as much of the following information as possible:

- A description of the vulnerability.
- The affected version or commit.
- The relevant component or subsystem.
- Steps to reproduce the issue.
- The potential security impact.
- Any known workarounds or suggested mitigations.

A proof of concept is helpful, but please avoid including unnecessary sensitive information or exploiting the vulnerability against systems you do not own or have permission to test.

## Security Scope

Security reports relevant to SimWeaver Junction may include, but are not limited to:

- Remote code execution or arbitrary command execution.
- Authentication or authorization weaknesses.
- Unintended access to federation or bridge configuration.
- Exposure of sensitive information through logs, APIs, or network interfaces.
- Vulnerabilities in network communication or protocol handling.
- Denial-of-service vulnerabilities affecting the application.
- Unsafe handling of external data, FOM files, configuration files, or protocol messages.
- Vulnerabilities in dependencies that affect the security of Junction.

Particular attention is given to issues that could affect systems operating in distributed simulation environments.

## Security Context and Intended Use

SimWeaver Junction is intended to be deployed as part of a controlled simulation or integration environment.

The project does not assume that all connected networks, federations, participants, or external data sources are inherently trusted.

Deployments should follow appropriate security practices, including:

- Restricting network access to trusted and authorized systems.
- Protecting configuration files and credentials.
- Avoiding unnecessary exposure of management interfaces.
- Applying operating system and network-level security controls.
- Keeping the application and its dependencies up to date.

The software should not be considered a substitute for appropriate security controls in the surrounding infrastructure.

## Response and Disclosure

The project maintainers will make reasonable efforts to:

- Acknowledge receipt of a security report within a reasonable timeframe.
- Assess the reported issue and its potential impact.
- Communicate with the reporter during the investigation.
- Develop and release a fix where appropriate.
- Coordinate public disclosure with the reporter.

We ask reporters to allow the maintainers reasonable time to investigate and address a vulnerability before publicly disclosing technical details.

Once a vulnerability has been addressed, the maintainers may publish a security advisory describing the issue, its impact, affected versions, and available mitigations.

## Responsible Disclosure

We appreciate responsible security research and the efforts of individuals who help improve the security of SimWeaver Junction.

We ask security researchers to:

- Avoid accessing, modifying, or disrupting systems without authorization.
- Avoid actions that could compromise the confidentiality or availability of third-party systems.
- Keep vulnerability details confidential until coordinated disclosure has been agreed upon.
- Provide sufficient information to help reproduce and verify the issue.

Thank you for helping keep SimWeaver Junction and its users secure.
