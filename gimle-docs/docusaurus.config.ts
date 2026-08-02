import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'Gimlé',
  tagline: 'Karaf/OSGi-style module lifecycle meets Kubernetes-style orchestration, all on the JVM',
  favicon: 'img/favicon.ico',

  // Future flags, see https://docusaurus.io/docs/api/docusaurus-config#future
  future: {
    v4: true, // Improve compatibility with the upcoming Docusaurus v4
  },

  // Hosting target is not decided yet (see claudedocs/docs-site-design.md §1) -- placeholder until
  // a real static host is chosen.
  url: 'https://example.com',
  baseUrl: '/',

  onBrokenLinks: 'throw',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/', // the docs tree IS the site root, no separate landing page
          sidebarPath: './sidebars.ts',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themes: ['@docusaurus/theme-mermaid'],
  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Gimlé',
      logo: {
        alt: 'Gimlé logo',
        src: 'img/logo.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          // `pathname://` is Docusaurus's own escape hatch for linking to a literal static path
          // that isn't a Docusaurus route: it skips both baseUrl-prefixing and the broken-link
          // checker. Needed here because /javadoc/ is a passthrough-copied static directory
          // (populated by `mvn gimle:docs` -- see gimle-docs/pom.xml and gimle-maven-plugin's
          // DocsMojo), not a route -- plain `to`/`href` both get validated against known routes
          // by Docusaurus 3.x regardless, and fail the build since that directory doesn't even
          // exist on disk until the Maven/DocsMojo copy step has run once.
          href: 'pathname:///javadoc/',
          label: 'API Reference',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      copyright: `Copyright © ${new Date().getFullYear()} Gimlé.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
