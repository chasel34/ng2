/** NGA 官方域名。第一个是默认域名（API 文档 §0.1）。 */
export const NGA_HOSTS = [
  'https://bbs.nga.cn',
  'https://ngabbs.com',
  'https://bbs.ngacn.cc',
  'https://nga.178.com',
  'https://nga.donews.com',
] as const

export const DEFAULT_NGA_HOST = NGA_HOSTS[0]

/**
 * UA 档位（API 文档 §0.3）。服务端校验客户端身份，必须伪装。
 *
 * Android v4 的现行做法是 UA 用系统 WebView UA、身份放辅助头 `X-User-Agent: Nga_Official`，
 * 所以 `webview` 档的 UA 值应由设备侧注入（见 NgaFetcherOptions.webViewUserAgent），
 * 这里的常量只是拿不到系统 UA 时的兜底。
 */
export const USER_AGENT_PROFILES = {
  /** 官方安卓客户端 UA，写死版本号 */
  official: 'Nga_Official/80024(Android12)',
  /** 系统 WebView UA 兜底值 */
  webview:
    'Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
  /** MNGA 对 read.php 强制使用，实测更不容易被封 */
  windowsPhone: 'NGA_WP_JW/(;WINDOWS)',
  /** 网页兜底用 */
  desktop:
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
} as const

export type UserAgentProfile = keyof typeof USER_AGENT_PROFILES

/** 客户端身份辅助头的值 */
export const X_USER_AGENT = 'Nga_Official'

/**
 * 返回格式参数（API 文档 §0.4）。被封时交替尝试不同格式可绕过，
 * 这是反封锁链（ADR-0002）「格式参数交替」那一档要遍历的集合。
 */
export const RESPONSE_FORMATS = {
  /** 紧凑 JSON，nuke.php / app_api.php 通用 */
  json: [['__output', '8']],
  /** 详细 JSON */
  jsonVerbose: [['__output', '11']],
  /** JS 变量赋值包裹的 JSON，Android 常用 */
  jsonLite: [['lite', 'js']],
  /** XML，MNGA 对 thread/read/post/forum.php 的首选 */
  xml: [['lite', 'xml']],
  /** 紧凑 XML，与 lite=xml 等价的备用格式 */
  xmlCompact: [['__output', '10']],
  /** 不带格式参数 = 网页 HTML，Web 反解与网页兜底走这条 */
  html: [],
} as const satisfies Record<string, readonly (readonly [string, string])[]>

export type ResponseFormat = keyof typeof RESPONSE_FORMATS

/** JSON 家族的格式，本层能直接清洗+解析 */
export const JSON_FORMATS: readonly ResponseFormat[] = ['json', 'jsonVerbose', 'jsonLite']

/**
 * 「假错误」白名单（API 文档 §0.7）：出现这些词的 error 视为成功。
 * 注意 `找不到用户` 也在里面，调用方拿到 fakeError 后要另判 data 是否为空。
 */
export const FAKE_ERROR_MESSAGES = [
  '完毕',
  '没找到',
  '没有符合条件的结果',
  '今天已经签到',
  '找不到用户',
] as const
