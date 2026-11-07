/**
 * dsh-phone-control — 让 agent 真正操控手机。
 *
 * ## 链路
 *
 * ```
 * 模型 → phone 工具(这个插件) → HTTP 127.0.0.1:3087 → 手机 App 的端点
 *                                                   → Shizuku shell 侧执行
 * ```
 *
 * 手机是 Shizuku 权限的持有者,桌面拿不到 Binder,所以端点开在手机上、由这个
 * 插件反向调用。端口只监听回环,必须带 token(token 由 App 生成,存在它自己的
 * 私有目录里,本插件通过 `phoneToken` 配置读取)。
 *
 * ## 为什么工具只有一个而不是二十个
 *
 * 每个动作一个工具会让工具列表爆炸,而且模型要在一堆同构工具里挑。这里用
 * **一个 `phone` 工具 + action 字段**,参数按 action 校验:模型看到的是一份
 * 说明书,调用面只有一处。
 *
 * ## 安全
 *
 * - 插件本身不执行任何手机操作,只发 HTTP;
 * - App 侧端点只认回环 + token,并且设置里的总开关关掉时根本不启动;
 * - 真正的执行仍在 shell 侧,由固定动词白名单把关。
 */

import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'dsh-phone-control'

/** 只依赖 tools 与 systemPrompt:其余都是可选的运行时探测。 */
export const inject = ['tools', 'systemPrompt']

export interface PhoneControlConfig {
  /** App 端端点。默认 127.0.0.1:3087。 */
  endpoint: string
  /** 与 App 共享的 token。留空则每次调用时尝试从 tokenFile 读。 */
  token: string
  /** token 文件路径(adb pull 下来的那份)。留空则不读。 */
  tokenFile: string
  /** 单次 HTTP 超时(毫秒)。截图可能慢一点。 */
  timeoutMs: number
}

const DEFAULT_ENDPOINT = 'http://127.0.0.1:3087'

/** 一次 HTTP 调用,统一超时与错误映射。 */
async function callPhone(
  config: PhoneControlConfig,
  route: string,
  body?: unknown
): Promise<Record<string, unknown>> {
  const url = `${config.endpoint.replace(/\/$/, '')}${route}`
  const token = await resolveToken(config)
  if (token === '') {
    throw new Error(
      '手机操控没有就绪:App 里还没打开「手机操控」开关,或没读到 token。' +
        '请先在手机 App 的设置里打开开关并完成 Shizuku 授权。'
    )
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), config.timeoutMs)
  try {
    const res = await fetch(url, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {
        'x-dsh-phone-token': token,
        ...(body === undefined ? {} : { 'content-type': 'application/json' })
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: controller.signal
    })
    const text = await res.text()
    let parsed: Record<string, unknown>
    try {
      parsed = JSON.parse(text) as Record<string, unknown>
    } catch {
      throw new Error(`手机端点返回了非 JSON(HTTP ${res.status}):${text.slice(0, 200)}`)
    }
    if (!res.ok || parsed.ok !== true) {
      const reason = typeof parsed.error === 'string' ? parsed.error : `HTTP ${res.status}`
      throw new Error(`手机操控失败:${reason}`)
    }
    return parsed
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') {
      throw new Error(
        `手机端点 ${url} 超时(${config.timeoutMs}ms)。检查:手机 App 是否在运行、` +
          '设置里「手机操控」是否打开、adb reverse 是否仍然有效。'
      )
    }
    if (err instanceof TypeError) {
      throw new Error(
        `连不上手机端点 ${url}。手机 App 需要把 3087 转发到桌面:` +
          '`adb reverse tcp:3087 tcp:3087`(或让 App 走同一台机器的隧道)。'
      )
    }
    throw err
  } finally {
    clearTimeout(timer)
  }
}

/** token 优先用配置里的,否则读文件。 */
async function resolveToken(config: PhoneControlConfig): Promise<string> {
  if (config.token.trim() !== '') return config.token.trim()
  if (config.tokenFile.trim() === '') return ''
  try {
    const { readFile } = await import('node:fs/promises')
    return (await readFile(config.tokenFile, 'utf8')).trim()
  } catch {
    return ''
  }
}

/** 每个 action 需要的必填参数,用来在发出去之前就给出清楚的报错。 */
const REQUIRED: Record<string, string[]> = {
  tap: ['x', 'y'],
  double_tap: ['x', 'y'],
  long_press: ['x', 'y'],
  swipe: ['x', 'y', 'x2', 'y2'],
  drag: ['x', 'y', 'x2', 'y2'],
  scroll: [],
  text: ['text'],
  key: ['key'],
  hard_key: ['key'],
  launch: ['pkg'],
  force_stop: ['pkg'],
  uninstall: ['pkg'],
  rotate: ['orientation'],
  brightness: [],
  volume: [],
  keep_awake: ['on'],
  toggle: ['feature', 'on'],
  clear_background: [],
  foreground: [],
  recents: [],
  notifications: [],
  get_clipboard: [],
  set_clipboard: ['text'],
  device_info: []
}

export function definePhoneTool(config: PhoneControlConfig) {
  return defineTool({
    name: 'phone',
    description:
      '操控用户这台 Android 手机(通过 Shizuku 的 shell 权限):截图、点按、滑动、输入文字、' +
      '按物理键、启动/停止应用、调亮度音量、读通知与剪贴板。' +
      '用户已经明确要求你操作手机时才用。先 action=status 确认可用;' +
      '不知道屏幕上有什么就先 action=screen 看一眼再决定点哪里。' +
      '这是用户的真实手机:会真的点、真的发消息、真的卸载应用,不要试探性乱点。',
    parameters: {
      action: {
        type: 'string',
        required: true,
        description:
          '要做的动作:status | screen | tap | double_tap | long_press | swipe | drag | scroll | ' +
          'text | key | hard_key | launch | force_stop | uninstall | rotate | brightness | volume | ' +
          'keep_awake | toggle | clear_background | foreground | recents | notifications | ' +
          'get_clipboard | set_clipboard | device_info | apps'
      },
      x: { type: 'number', description: '屏幕 X 坐标(设备像素,tap/swipe/drag 用)' },
      y: { type: 'number', description: '屏幕 Y 坐标(设备像素)' },
      x2: { type: 'number', description: '终点 X(swipe/drag)' },
      y2: { type: 'number', description: '终点 Y(swipe/drag)' },
      ms: { type: 'number', description: '时长毫秒(long_press 默认 600,swipe 默认 260)' },
      text: { type: 'string', description: 'text / set_clipboard 的文本' },
      key: {
        type: 'string',
        description:
          'key/hard_key 的键名:back | home | recents | notifications | enter | delete | power | ' +
          'volume_up | volume_down | wake | sleep | tab | escape | space | menu | search | camera | ' +
          'mute | play_pause | next | previous | flashlight'
      },
      pkg: { type: 'string', description: '应用包名(launch / force_stop / uninstall)' },
      direction: { type: 'string', description: 'scroll 方向:up | down | left | right' },
      distance: { type: 'number', description: 'scroll 距离 1–10(默认 5)' },
      orientation: { type: 'number', description: 'rotate 角度:0 | 90 | 180 | 270' },
      level: { type: 'number', description: 'brightness 0–255 / volume 0–100;省略则读取当前值' },
      stream: { type: 'string', description: 'volume 通道:music | ring | alarm | notification | call' },
      on: { type: 'boolean', description: 'keep_awake / toggle 的开关状态' },
      feature: {
        type: 'string',
        description: 'toggle 的目标:wifi | data | airplane | bluetooth | location | rotation | night'
      }
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: true,
        properties: {
          ok: { type: 'boolean', required: true },
          detail: { type: 'string' }
        }
      },
      render: (_args, value) => [{ type: 'text', text: String((value as { detail?: string }).detail ?? 'ok') }]
    },
    // 点按与输入天然有顺序,不能并发。
    isConcurrencySafe: () => false,
    async execute(rawArgs) {
      const args = rawArgs as Record<string, unknown>
      const action = String(args.action ?? '').trim().toLowerCase()
      if (action === '') throw new Error('action 不能为空')

      // 本地就能回答的两个动作,不必打到手机。
      if (action === 'status') {
        const st = await callPhone(config, '/phone/status')
        const detail = [
          `就绪: ${st.ready === true ? '是' : '否'}`,
          `Shizuku: 安装=${bool(st.installed)} 运行=${bool(st.running)} 授权=${bool(st.granted)}`,
          `特权通道: ${st.connected === true ? '已连接' : '未连接'}`,
          `屏幕: ${String(st.width ?? '?')}×${String(st.height ?? '?')}`,
          st.reason ? `原因: ${String(st.reason)}` : ''
        ]
          .filter(Boolean)
          .join('\n')
        return { ok: st.ready === true, detail }
      }

      if (action === 'screen') {
        const shot = await callPhone(config, '/phone/screen')
        const w = Number(shot.width ?? 0)
        const h = Number(shot.height ?? 0)
        const png = String(shot.png ?? '')
        return {
          ok: png !== '',
          detail:
            `已截屏 ${w}×${h}(base64 PNG,${png.length} 字符)。\n` +
            `点按坐标请用设备像素:左上角是 (0,0),右下角是 (${w - 1},${h - 1})。\n` +
            `PNG base64:\n${png}`
        }
      }

      if (action === 'apps') {
        const res = await callPhone(config, '/phone/apps')
        const list = Array.isArray(res.apps) ? res.apps : []
        const lines = list
          .slice(0, 200)
          .map((a) => `- ${String((a as Record<string, unknown>).label ?? '')}  ${String((a as Record<string, unknown>).pkg ?? '')}`)
        return { ok: true, detail: `已安装应用 ${list.length} 个:\n${lines.join('\n')}` }
      }

      const required = REQUIRED[action]
      if (required === undefined) {
        throw new Error(
          `不支持的动作 "${action}"。可用:${Object.keys(REQUIRED).join(' | ')} | status | screen | apps`
        )
      }
      for (const key of required) {
        if (args[key] === undefined || args[key] === null || args[key] === '') {
          throw new Error(`动作 ${action} 缺少参数 ${key}`)
        }
      }

      const payload: Record<string, unknown> = { op: action }
      for (const key of [
        'x',
        'y',
        'x2',
        'y2',
        'ms',
        'text',
        'key',
        'pkg',
        'direction',
        'distance',
        'orientation',
        'level',
        'stream',
        'on',
        'feature'
      ]) {
        if (args[key] !== undefined) payload[key] = args[key]
      }

      const res = await callPhone(config, '/phone/act', payload)
      const parts = [`已执行 ${action}`]
      if (res.value !== undefined) parts.push(`结果: ${String(res.value)}`)
      if (res.pkg !== undefined && String(res.pkg) !== '') parts.push(`前台: ${String(res.pkg)}`)
      if (Array.isArray(res.packages)) parts.push(`包名: ${res.packages.join(', ')}`)
      if (Array.isArray(res.texts)) {
        parts.push(res.texts.length === 0 ? '当前没有通知' : `通知:\n${res.texts.join('\n')}`)
      }
      if (res.text !== undefined && String(res.text) !== '') parts.push(String(res.text))
      return { ok: true, detail: parts.join('\n') }
    }
  })
}

function bool(v: unknown): string {
  return v === true ? '是' : '否'
}

/** Cordis 插件入口。 */
export function apply(ctx: {
  tools: { register: (tool: unknown) => void }
  systemPrompt: { section: (section: { name: string; order: number; text: string }) => void }
}, rawConfig: Partial<PhoneControlConfig> = {}) {
  const config: PhoneControlConfig = {
    endpoint: rawConfig.endpoint?.trim() || DEFAULT_ENDPOINT,
    token: rawConfig.token ?? '',
    tokenFile: rawConfig.tokenFile ?? '',
    timeoutMs: rawConfig.timeoutMs ?? 30_000
  }

  ctx.tools.register(definePhoneTool(config))

  ctx.systemPrompt.section({
    name: 'tool:phone',
    order: 120,
    text:
      '你可以通过 `phone` 工具操控用户这台 Android 手机(Shizuku shell 权限)。' +
      '用户明确要求"帮我操作手机/点一下/截图看看/打开某应用"时才用。' +
      '流程:先 action=status 确认就绪;需要看屏幕就 action=screen,它返回一张 base64 PNG,你可以据此决定坐标;' +
      '点按用设备像素坐标。这是真实设备,会真的产生效果(发消息、安装/卸载),不要盲目试错;' +
      '危险动作(卸载、强制停止、改系统开关)先向用户确认。'
  })
}
