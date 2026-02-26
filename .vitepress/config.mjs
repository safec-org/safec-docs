import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'SafeC',
  description: 'The SafeC Programming Language — Safe, Deterministic Systems Programming',
  base: '/safec-docs/',
  head: [['link', { rel: 'icon', href: '/safec-docs/favicon.ico' }]],
  themeConfig: {
    logo: '/logo.svg',
    nav: [
      { text: 'Guide', link: '/guide/introduction' },
      { text: 'Reference', link: '/reference/types' },
      { text: 'Standard Library', link: '/stdlib/' },
      { text: 'Advanced', link: '/advanced/compiler' },
      { text: 'GitHub', link: 'https://github.com/safec-org/SafeC' }
    ],
    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/introduction' },
            { text: 'Getting Started', link: '/guide/getting-started' },
            { text: 'Design Philosophy', link: '/guide/design' },
            { text: 'Comparison', link: '/guide/comparison' }
          ]
        }
      ],
      '/reference/': [
        {
          text: 'Language Reference',
          items: [
            { text: 'Types', link: '/reference/types' },
            { text: 'Operators', link: '/reference/operators' },
            { text: 'Literals & Qualifiers', link: '/reference/literals' },
            { text: 'Memory & Regions', link: '/reference/memory' },
            { text: 'Functions', link: '/reference/functions' },
            { text: 'Generics', link: '/reference/generics' },
            { text: 'Control Flow', link: '/reference/control-flow' },
            { text: 'Safety', link: '/reference/safety' },
            { text: 'C Interop (FFI)', link: '/reference/ffi' },
            { text: 'Concurrency', link: '/reference/concurrency' },
            { text: 'Bare-Metal', link: '/reference/baremetal' },
            { text: 'Preprocessor', link: '/reference/preprocessor' },
            { text: 'Overflow Operators', link: '/reference/overflow' }
          ]
        }
      ],
      '/stdlib/': [
        {
          text: 'Standard Library',
          items: [
            { text: 'Overview', link: '/stdlib/' },
          ]
        },
        {
          text: 'Core',
          items: [
            { text: 'mem', link: '/stdlib/mem' },
            { text: 'io', link: '/stdlib/io' },
            { text: 'str', link: '/stdlib/str' },
            { text: 'math', link: '/stdlib/math' },
            { text: 'thread', link: '/stdlib/thread' },
            { text: 'atomic', link: '/stdlib/atomic' },
          ]
        },
        {
          text: 'Collections',
          items: [
            { text: 'All Collections', link: '/stdlib/collections' },
          ]
        },
        {
          text: 'Ecosystem',
          items: [
            { text: 'Allocators', link: '/stdlib/allocators' },
            { text: 'Synchronization', link: '/stdlib/sync' },
            { text: 'Networking', link: '/stdlib/net' },
            { text: 'Filesystems', link: '/stdlib/fs' },
            { text: 'DSP & Real-Time', link: '/stdlib/dsp' },
            { text: 'Cryptography', link: '/stdlib/crypto' },
            { text: 'Debugging', link: '/stdlib/debug' },
          ]
        },
      ],
      '/advanced/': [
        {
          text: 'Advanced',
          items: [
            { text: 'Compiler Architecture', link: '/advanced/compiler' },
            { text: 'Package Manager', link: '/advanced/safeguard' },
            { text: 'Safety Model', link: '/advanced/safety-model' },
            { text: 'Compile-Time Introspection', link: '/advanced/introspection' },
            { text: 'Formal Verification', link: '/advanced/formal-proofs' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/safec-org/SafeC' }
    ],
    search: { provider: 'local' },
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright 2026 SafeC Contributors'
    }
  }
})
