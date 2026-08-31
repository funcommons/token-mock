import { defineConfig } from 'vitepress'

// zh-CN 为默认语言(根路径),英文位于 /en/
const zhNav = [
  { text: '指南', link: '/guide/overview' },
  { text: 'API 参考', link: '/guide/api-reference' },
  { text: '配置', link: '/guide/configuration' },
  { text: '设计', link: '/guide/design' },
  { text: 'v1.0.0', link: 'https://github.com/funcommons/token-mock/releases/tag/v1.0.0' },
]

const enNav = [
  { text: 'Guide', link: '/en/guide/overview' },
  { text: 'API Reference', link: '/en/guide/api-reference' },
  { text: 'Configuration', link: '/en/guide/configuration' },
  { text: 'Design', link: '/en/guide/design' },
  { text: 'v1.0.0', link: 'https://github.com/funcommons/token-mock/releases/tag/v1.0.0' },
]

const zhSidebar = {
  '/guide/': [
    {
      text: '入门',
      items: [
        { text: '概述', link: '/guide/overview' },
        { text: '快速开始', link: '/guide/quickstart' },
        { text: '协议与厂商路由', link: '/guide/protocols' },
      ],
    },
    {
      text: '接口',
      items: [
        { text: 'API 参考', link: '/guide/api-reference' },
        { text: '多模态', link: '/guide/modalities' },
      ],
    },
    {
      text: '演练',
      items: [
        { text: '故障注入', link: '/guide/fault-injection' },
        { text: '限流', link: '/guide/rate-limit' },
      ],
    },
    {
      text: '运维',
      items: [
        { text: '管理与统计', link: '/guide/admin-api' },
        { text: '配置参考', link: '/guide/configuration' },
      ],
    },
    {
      text: '设计',
      items: [{ text: '架构与设计', link: '/guide/design' }],
    },
    {
      text: '关于',
      items: [
        { text: '更新日志', link: '/changelog' },
        { text: '许可证', link: '/license' },
      ],
    },
  ],
}

const enSidebar = {
  '/en/guide/': [
    {
      text: 'Introduction',
      items: [
        { text: 'Overview', link: '/en/guide/overview' },
        { text: 'Quickstart', link: '/en/guide/quickstart' },
        { text: 'Protocols & Vendor Routing', link: '/en/guide/protocols' },
      ],
    },
    {
      text: 'API',
      items: [
        { text: 'API Reference', link: '/en/guide/api-reference' },
        { text: 'Multimodal', link: '/en/guide/modalities' },
      ],
    },
    {
      text: 'Chaos',
      items: [
        { text: 'Fault Injection', link: '/en/guide/fault-injection' },
        { text: 'Rate Limiting', link: '/en/guide/rate-limit' },
      ],
    },
    {
      text: 'Operations',
      items: [
        { text: 'Admin & Stats', link: '/en/guide/admin-api' },
        { text: 'Configuration', link: '/en/guide/configuration' },
      ],
    },
    {
      text: 'Design',
      items: [{ text: 'Architecture & Design', link: '/en/guide/design' }],
    },
    {
      text: 'About',
      items: [
        { text: 'Changelog', link: '/en/changelog' },
        { text: 'License', link: '/en/license' },
      ],
    },
  ],
}

const footer = {
  message: 'Released under the Apache License 2.0. | 基于 Apache 2.0 协议发布',
  copyright: 'Copyright © 2026 fun.commons',
}

export default defineConfig({
  title: 'token-mock',
  description: '多厂商 LLM API Mock 服务 — 集成测试 / 故障演练 / Demo',
  lang: 'zh-CN',
  base: '/token-mock/',
  lastUpdated: true,
  cleanUrls: true,
  ignoreDeadLinks: true,

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/token-mock/favicon.svg' }],
    ['meta', { name: 'theme-color', content: '#059669' }],
    ['meta', { property: 'og:title', content: 'token-mock' }],
    ['meta', { property: 'og:description', content: '多厂商 LLM API Mock 服务 — 集成测试 / 故障演练 / Demo' }],
  ],

  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      title: 'token-mock',
      description: '多厂商 LLM API Mock 服务 — 集成测试 / 故障演练 / Demo',
      themeConfig: {
        nav: zhNav,
        sidebar: zhSidebar,
        socialLinks: [{ icon: 'github', link: 'https://github.com/funcommons/token-mock' }],
        footer,
        outline: { level: [2, 3], label: '本页内容' },
        editLink: {
          pattern: 'https://github.com/funcommons/token-mock/edit/main/site/:path',
          text: '在 GitHub 上编辑此页',
        },
        search: { provider: 'local' },
        lastUpdated: { text: '最后更新于' },
        docFooter: { prev: '上一页', next: '下一页' },
        returnToTopLabel: '回到顶部',
        sidebarMenuLabel: '菜单',
        darkModeSwitchLabel: '主题',
        lightModeSwitchTitle: '切换到浅色模式',
        darkModeSwitchTitle: '切换到深色模式',
      },
    },
    en: {
      label: 'English',
      lang: 'en-US',
      title: 'token-mock',
      description: 'Multi-vendor LLM API mock server for integration testing, chaos drills and demos',
      themeConfig: {
        nav: enNav,
        sidebar: enSidebar,
        socialLinks: [{ icon: 'github', link: 'https://github.com/funcommons/token-mock' }],
        footer,
        outline: { level: [2, 3], label: 'On this page' },
        editLink: {
          pattern: 'https://github.com/funcommons/token-mock/edit/main/site/:path',
          text: 'Edit this page on GitHub',
        },
        search: { provider: 'local' },
      },
    },
  },
})
