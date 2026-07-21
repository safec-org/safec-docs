import { defineConfig } from 'vitepress'

const enNav = [
  {
    text: 'Guide',
    items: [
      { text: 'Introduction', link: '/guide/introduction' },
      { text: 'Getting Started', link: '/guide/getting-started' },
      { text: 'Design Philosophy', link: '/guide/design' },
      { text: 'Comparison', link: '/guide/comparison' }
    ]
  },
  {
    text: 'Book',
    items: [
      { text: 'Preface', link: '/book/' },
      { text: '1. Getting Started', link: '/book/ch01-getting-started' },
      { text: '2. A First Program', link: '/book/ch02-first-program' },
      { text: '3. Common Concepts', link: '/book/ch03-common-concepts' },
      { text: '4. Functions', link: '/book/ch04-functions' },
      { text: '5. Understanding Regions', link: '/book/ch05-understanding-regions' },
      { text: '6. Structs and Methods', link: '/book/ch06-structs-and-methods' },
      { text: '7. Enums, Unions, and Match', link: '/book/ch07-enums-and-match' },
      { text: '8. Error Handling', link: '/book/ch08-error-handling' },
      { text: '9. Final Project', link: '/book/ch09-final-project' }
    ]
  },
  {
    text: 'Reference',
    items: [
      { text: 'Types', link: '/reference/types' },
      { text: 'Operators', link: '/reference/operators' },
      { text: 'Literals & Qualifiers', link: '/reference/literals' },
      { text: 'Memory & Regions', link: '/reference/memory' },
      { text: 'Functions', link: '/reference/functions' },
      { text: 'Generics', link: '/reference/generics' },
      { text: 'Polymorphism & OOP', link: '/reference/polymorphism' },
      { text: 'Functional Programming', link: '/reference/functional' },
      { text: 'Namespaces', link: '/reference/namespaces' },
      { text: 'Native SIMD', link: '/reference/simd' },
      { text: 'C Superset', link: '/reference/c-superset' },
      { text: 'Control Flow', link: '/reference/control-flow' },
      { text: 'Safety', link: '/reference/safety' },
      { text: 'C Interop (FFI)', link: '/reference/ffi' },
      { text: 'Concurrency', link: '/reference/concurrency' },
      { text: 'Bare-Metal', link: '/reference/baremetal' },
      { text: 'Preprocessor', link: '/reference/preprocessor' },
      { text: 'Overflow Operators', link: '/reference/overflow' }
    ]
  },
  {
    text: 'Standard Library',
    items: [
      { text: 'Overview', link: '/stdlib/' },
      { text: 'mem', link: '/stdlib/mem' },
      { text: 'io', link: '/stdlib/io' },
      { text: 'str', link: '/stdlib/str' },
      { text: 'math', link: '/stdlib/math' },
      { text: 'thread', link: '/stdlib/thread' },
      { text: 'atomic', link: '/stdlib/atomic' },
      { text: 'Serialization', link: '/stdlib/serial' },
      { text: 'Collections', link: '/stdlib/collections' },
      { text: 'Allocators', link: '/stdlib/allocators' },
      { text: 'Synchronization', link: '/stdlib/sync' },
      { text: 'IPC', link: '/stdlib/ipc' },
      { text: 'Real-Time Scheduler', link: '/stdlib/sched' },
      { text: 'Networking', link: '/stdlib/net' },
      { text: 'Filesystems', link: '/stdlib/fs' },
      { text: 'DSP & Real-Time', link: '/stdlib/dsp' },
      { text: 'Cryptography', link: '/stdlib/crypto' },
      { text: 'Debugging', link: '/stdlib/debug' },
      { text: 'SIMD', link: '/stdlib/simd' },
      { text: 'Hardware Abstraction', link: '/stdlib/hal' },
      { text: 'Interrupts & MMIO', link: '/stdlib/interrupt' },
      { text: 'Kernel Primitives', link: '/stdlib/kernel' },
      { text: 'Testing & Benchmarking', link: '/stdlib/testing' }
    ]
  },
  {
    text: 'Advanced',
    items: [
      { text: 'Compiler Architecture', link: '/advanced/compiler' },
      { text: 'Package Manager', link: '/advanced/safeguard' },
      { text: 'scx Templating', link: '/advanced/scx' },
      { text: 'Safety Model', link: '/advanced/safety-model' },
      { text: 'Compile-Time Introspection', link: '/advanced/introspection' }
    ]
  },
  { text: 'Benchmarks', link: '/benchmarks' },
  { text: 'GitHub', link: 'https://github.com/safec-org/SafeC' }
]

const enSidebar = {
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
  '/book/': [
    {
      text: 'The SafeC Book',
      items: [
        { text: 'Preface', link: '/book/' },
        { text: '1. Getting Started', link: '/book/ch01-getting-started' },
        { text: '2. A First Program', link: '/book/ch02-first-program' },
        { text: '3. Common Concepts', link: '/book/ch03-common-concepts' },
        { text: '4. Functions', link: '/book/ch04-functions' },
        { text: '5. Understanding Regions', link: '/book/ch05-understanding-regions' },
        { text: '6. Structs and Methods', link: '/book/ch06-structs-and-methods' },
        { text: '7. Enums, Unions, and Match', link: '/book/ch07-enums-and-match' },
        { text: '8. Error Handling', link: '/book/ch08-error-handling' },
        { text: '9. Final Project', link: '/book/ch09-final-project' }
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
        { text: 'Polymorphism & OOP', link: '/reference/polymorphism' },
        { text: 'Functional Programming', link: '/reference/functional' },
        { text: 'Namespaces', link: '/reference/namespaces' },
        { text: 'Native SIMD', link: '/reference/simd' },
        { text: 'C Superset', link: '/reference/c-superset' },
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
      text: 'Serialization',
      items: [
        { text: 'JSON / XML / HTML / CSV / YAML', link: '/stdlib/serial' },
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
        { text: 'IPC', link: '/stdlib/ipc' },
        { text: 'Real-Time Scheduler', link: '/stdlib/sched' },
        { text: 'Networking', link: '/stdlib/net' },
        { text: 'Filesystems', link: '/stdlib/fs' },
        { text: 'DSP & Real-Time', link: '/stdlib/dsp' },
        { text: 'Cryptography', link: '/stdlib/crypto' },
        { text: 'Debugging', link: '/stdlib/debug' },
      ]
    },
    {
      text: 'SIMD & Embedded',
      items: [
        { text: 'SIMD', link: '/stdlib/simd' },
        { text: 'Hardware Abstraction', link: '/stdlib/hal' },
        { text: 'Interrupts & MMIO', link: '/stdlib/interrupt' },
        { text: 'Kernel Primitives', link: '/stdlib/kernel' },
      ]
    },
    {
      text: 'Development',
      items: [
        { text: 'Testing & Benchmarking', link: '/stdlib/testing' },
      ]
    },
  ],
  '/advanced/': [
    {
      text: 'Advanced',
      items: [
        { text: 'Compiler Architecture', link: '/advanced/compiler' },
        { text: 'Package Manager', link: '/advanced/safeguard' },
        { text: 'scx Templating', link: '/advanced/scx' },
        { text: 'Safety Model', link: '/advanced/safety-model' },
        { text: 'Compile-Time Introspection', link: '/advanced/introspection' }
      ]
    }
  ]
}

// Korean (ko) locale — mirrors enNav/enSidebar one-to-one, /ko/ prefixed,
// Korean labels. Kept as a fully separate literal (not generated from
// enNav) so labels/links can be added incrementally per page as each is
// translated, without a prefixing helper masking a typo in either tree.
const koNav = [
  {
    text: '가이드',
    items: [
      { text: '소개', link: '/ko/guide/introduction' },
      { text: '시작하기', link: '/ko/guide/getting-started' },
      { text: '설계 철학', link: '/ko/guide/design' },
      { text: '비교', link: '/ko/guide/comparison' }
    ]
  },
  {
    text: '북',
    items: [
      { text: '서문', link: '/ko/book/' },
      { text: '1. 시작하기', link: '/ko/book/ch01-getting-started' },
      { text: '2. 첫 프로그램', link: '/ko/book/ch02-first-program' },
      { text: '3. 공통 개념', link: '/ko/book/ch03-common-concepts' },
      { text: '4. 함수', link: '/ko/book/ch04-functions' },
      { text: '5. 리전 이해하기', link: '/ko/book/ch05-understanding-regions' },
      { text: '6. 구조체와 메서드', link: '/ko/book/ch06-structs-and-methods' },
      { text: '7. 열거형, 유니온, match', link: '/ko/book/ch07-enums-and-match' },
      { text: '8. 에러 처리', link: '/ko/book/ch08-error-handling' },
      { text: '9. 최종 프로젝트', link: '/ko/book/ch09-final-project' }
    ]
  },
  {
    text: '레퍼런스',
    items: [
      { text: '타입', link: '/ko/reference/types' },
      { text: '연산자', link: '/ko/reference/operators' },
      { text: '리터럴과 한정자', link: '/ko/reference/literals' },
      { text: '메모리와 리전', link: '/ko/reference/memory' },
      { text: '함수', link: '/ko/reference/functions' },
      { text: '제네릭', link: '/ko/reference/generics' },
      { text: '다형성과 OOP', link: '/ko/reference/polymorphism' },
      { text: '함수형 프로그래밍', link: '/ko/reference/functional' },
      { text: '네임스페이스', link: '/ko/reference/namespaces' },
      { text: '네이티브 SIMD', link: '/ko/reference/simd' },
      { text: 'C 슈퍼셋', link: '/ko/reference/c-superset' },
      { text: '제어 흐름', link: '/ko/reference/control-flow' },
      { text: '안전성', link: '/ko/reference/safety' },
      { text: 'C 상호운용 (FFI)', link: '/ko/reference/ffi' },
      { text: '동시성', link: '/ko/reference/concurrency' },
      { text: '베어메탈', link: '/ko/reference/baremetal' },
      { text: '전처리기', link: '/ko/reference/preprocessor' },
      { text: '오버플로 연산자', link: '/ko/reference/overflow' }
    ]
  },
  {
    text: '표준 라이브러리',
    items: [
      { text: '개요', link: '/ko/stdlib/' },
      { text: 'mem', link: '/ko/stdlib/mem' },
      { text: 'io', link: '/ko/stdlib/io' },
      { text: 'str', link: '/ko/stdlib/str' },
      { text: 'math', link: '/ko/stdlib/math' },
      { text: 'thread', link: '/ko/stdlib/thread' },
      { text: 'atomic', link: '/ko/stdlib/atomic' },
      { text: '직렬화', link: '/ko/stdlib/serial' },
      { text: '컬렉션', link: '/ko/stdlib/collections' },
      { text: '얼로케이터', link: '/ko/stdlib/allocators' },
      { text: '동기화', link: '/ko/stdlib/sync' },
      { text: 'IPC', link: '/ko/stdlib/ipc' },
      { text: '실시간 스케줄러', link: '/ko/stdlib/sched' },
      { text: '네트워킹', link: '/ko/stdlib/net' },
      { text: '파일시스템', link: '/ko/stdlib/fs' },
      { text: 'DSP와 실시간', link: '/ko/stdlib/dsp' },
      { text: '암호화', link: '/ko/stdlib/crypto' },
      { text: '디버깅', link: '/ko/stdlib/debug' },
      { text: 'SIMD', link: '/ko/stdlib/simd' },
      { text: '하드웨어 추상화', link: '/ko/stdlib/hal' },
      { text: '인터럽트와 MMIO', link: '/ko/stdlib/interrupt' },
      { text: '커널 프리미티브', link: '/ko/stdlib/kernel' },
      { text: '테스트와 벤치마킹', link: '/ko/stdlib/testing' }
    ]
  },
  {
    text: '고급',
    items: [
      { text: '컴파일러 아키텍처', link: '/ko/advanced/compiler' },
      { text: '패키지 매니저', link: '/ko/advanced/safeguard' },
      { text: 'scx 템플릿', link: '/ko/advanced/scx' },
      { text: '안전성 모델', link: '/ko/advanced/safety-model' },
      { text: '컴파일 타임 인트로스펙션', link: '/ko/advanced/introspection' }
    ]
  },
  { text: '벤치마크', link: '/ko/benchmarks' },
  { text: 'GitHub', link: 'https://github.com/safec-org/SafeC' }
]

const koSidebar = {
  '/ko/guide/': [
    {
      text: '시작하기',
      items: [
        { text: '소개', link: '/ko/guide/introduction' },
        { text: '시작하기', link: '/ko/guide/getting-started' },
        { text: '설계 철학', link: '/ko/guide/design' },
        { text: '비교', link: '/ko/guide/comparison' }
      ]
    }
  ],
  '/ko/book/': [
    {
      text: 'SafeC 북',
      items: [
        { text: '서문', link: '/ko/book/' },
        { text: '1. 시작하기', link: '/ko/book/ch01-getting-started' },
        { text: '2. 첫 프로그램', link: '/ko/book/ch02-first-program' },
        { text: '3. 공통 개념', link: '/ko/book/ch03-common-concepts' },
        { text: '4. 함수', link: '/ko/book/ch04-functions' },
        { text: '5. 리전 이해하기', link: '/ko/book/ch05-understanding-regions' },
        { text: '6. 구조체와 메서드', link: '/ko/book/ch06-structs-and-methods' },
        { text: '7. 열거형, 유니온, match', link: '/ko/book/ch07-enums-and-match' },
        { text: '8. 에러 처리', link: '/ko/book/ch08-error-handling' },
        { text: '9. 최종 프로젝트', link: '/ko/book/ch09-final-project' }
      ]
    }
  ],
  '/ko/reference/': [
    {
      text: '언어 레퍼런스',
      items: [
        { text: '타입', link: '/ko/reference/types' },
        { text: '연산자', link: '/ko/reference/operators' },
        { text: '리터럴과 한정자', link: '/ko/reference/literals' },
        { text: '메모리와 리전', link: '/ko/reference/memory' },
        { text: '함수', link: '/ko/reference/functions' },
        { text: '제네릭', link: '/ko/reference/generics' },
        { text: '다형성과 OOP', link: '/ko/reference/polymorphism' },
        { text: '함수형 프로그래밍', link: '/ko/reference/functional' },
        { text: '네임스페이스', link: '/ko/reference/namespaces' },
        { text: '네이티브 SIMD', link: '/ko/reference/simd' },
        { text: 'C 슈퍼셋', link: '/ko/reference/c-superset' },
        { text: '제어 흐름', link: '/ko/reference/control-flow' },
        { text: '안전성', link: '/ko/reference/safety' },
        { text: 'C 상호운용 (FFI)', link: '/ko/reference/ffi' },
        { text: '동시성', link: '/ko/reference/concurrency' },
        { text: '베어메탈', link: '/ko/reference/baremetal' },
        { text: '전처리기', link: '/ko/reference/preprocessor' },
        { text: '오버플로 연산자', link: '/ko/reference/overflow' }
      ]
    }
  ],
  '/ko/stdlib/': [
    {
      text: '표준 라이브러리',
      items: [
        { text: '개요', link: '/ko/stdlib/' },
      ]
    },
    {
      text: '코어',
      items: [
        { text: 'mem', link: '/ko/stdlib/mem' },
        { text: 'io', link: '/ko/stdlib/io' },
        { text: 'str', link: '/ko/stdlib/str' },
        { text: 'math', link: '/ko/stdlib/math' },
        { text: 'thread', link: '/ko/stdlib/thread' },
        { text: 'atomic', link: '/ko/stdlib/atomic' },
      ]
    },
    {
      text: '직렬화',
      items: [
        { text: 'JSON / XML / HTML / CSV / YAML', link: '/ko/stdlib/serial' },
      ]
    },
    {
      text: '컬렉션',
      items: [
        { text: '전체 컬렉션', link: '/ko/stdlib/collections' },
      ]
    },
    {
      text: '에코시스템',
      items: [
        { text: '얼로케이터', link: '/ko/stdlib/allocators' },
        { text: '동기화', link: '/ko/stdlib/sync' },
        { text: 'IPC', link: '/ko/stdlib/ipc' },
        { text: '실시간 스케줄러', link: '/ko/stdlib/sched' },
        { text: '네트워킹', link: '/ko/stdlib/net' },
        { text: '파일시스템', link: '/ko/stdlib/fs' },
        { text: 'DSP와 실시간', link: '/ko/stdlib/dsp' },
        { text: '암호화', link: '/ko/stdlib/crypto' },
        { text: '디버깅', link: '/ko/stdlib/debug' },
      ]
    },
    {
      text: 'SIMD와 임베디드',
      items: [
        { text: 'SIMD', link: '/ko/stdlib/simd' },
        { text: '하드웨어 추상화', link: '/ko/stdlib/hal' },
        { text: '인터럽트와 MMIO', link: '/ko/stdlib/interrupt' },
        { text: '커널 프리미티브', link: '/ko/stdlib/kernel' },
      ]
    },
    {
      text: '개발',
      items: [
        { text: '테스트와 벤치마킹', link: '/ko/stdlib/testing' },
      ]
    },
  ],
  '/ko/advanced/': [
    {
      text: '고급',
      items: [
        { text: '컴파일러 아키텍처', link: '/ko/advanced/compiler' },
        { text: '패키지 매니저', link: '/ko/advanced/safeguard' },
        { text: 'scx 템플릿', link: '/ko/advanced/scx' },
        { text: '안전성 모델', link: '/ko/advanced/safety-model' },
        { text: '컴파일 타임 인트로스펙션', link: '/ko/advanced/introspection' }
      ]
    }
  ]
}

export default defineConfig({
  base: '/safec-docs/',
  // Everything under public/benchmarks/ is a real static file (confirmed
  // present via `ls` repeatedly) — VitePress's dead-link checker
  // intermittently flags an inconsistent, varying subset of them anyway,
  // unrelated to whether the file actually exists; scoped to this one
  // directory rather than disabling the check globally, which would hide
  // genuinely broken links elsewhere on the site. Applies under both
  // locales since /ko/benchmarks links to the same unprefixed
  // /benchmarks/<file> static asset paths as the English page.
  ignoreDeadLinks: [
    /^\/benchmarks\//,
  ],
  head: [
    ['link', { rel: 'icon', type: 'image/x-icon', href: '/safec-docs/favicon.ico' }],
    ['link', { rel: 'icon', type: 'image/png', sizes: '32x32', href: '/safec-docs/favicon-32x32.png' }],
    ['link', { rel: 'icon', type: 'image/png', sizes: '16x16', href: '/safec-docs/favicon-16x16.png' }],
    ['link', { rel: 'apple-touch-icon', href: '/safec-docs/apple-touch-icon.png' }],
    ['link', { rel: 'manifest', href: '/safec-docs/site.webmanifest' }]
  ],
  locales: {
    root: {
      label: 'English',
      lang: 'en',
      title: 'SafeC',
      description: 'The SafeC Programming Language — Safe, Deterministic Systems Programming',
      themeConfig: {
        logo: '/Logo.svg',
        nav: enNav,
        sidebar: enSidebar,
        socialLinks: [
          { icon: 'github', link: 'https://github.com/safec-org/SafeC' }
        ],
        search: { provider: 'local' },
        footer: {
          message: 'Released under the MIT License.',
          copyright: 'Copyright 2026 SafeC Contributors'
        }
      }
    },
    ko: {
      label: '한국어',
      lang: 'ko-KR',
      link: '/ko/',
      title: 'SafeC',
      description: 'SafeC 프로그래밍 언어 — 안전하고 결정론적인 시스템 프로그래밍',
      themeConfig: {
        logo: '/Logo.svg',
        nav: koNav,
        sidebar: koSidebar,
        socialLinks: [
          { icon: 'github', link: 'https://github.com/safec-org/SafeC' }
        ],
        search: { provider: 'local' },
        footer: {
          message: 'MIT 라이선스로 배포됩니다.',
          copyright: 'Copyright 2026 SafeC Contributors'
        },
        docFooter: {
          prev: '이전 페이지',
          next: '다음 페이지'
        },
        outlineTitle: '이 페이지에서',
        returnToTopLabel: '맨 위로',
        langMenuLabel: '언어 변경',
        darkModeSwitchLabel: '테마 변경',
        sidebarMenuLabel: '메뉴',
        notFound: {
          title: '페이지를 찾을 수 없습니다',
          quote: '경로를 확인해 주세요. 아직 번역되지 않은 페이지일 수 있습니다.',
          linkLabel: '홈으로',
          linkText: '홈으로 돌아가기'
        }
      }
    }
  }
})
