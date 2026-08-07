/**
 * core/local —— 纯客户端算法：服务端不提供、必须本机算出来的那些东西
 * （API 文档 §5 第 11 条）。纯 TS、零 RN 依赖，不发请求。
 */

export {
  decodeAnonymousName,
  isAnonymousAuthor,
  resolveAuthorName,
  type AnonymousName,
} from './anonymous'
export {
  PLAIN_TITLE_STYLE,
  decodeTitleStyle,
  parseTopicMisc,
  titleStyleFromMask,
  type TitleColor,
  type TitleStyle,
  type TitleStyleSource,
  type TopicMisc,
} from './title-style'
