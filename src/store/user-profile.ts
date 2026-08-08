import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import {
  fetchUserAvatar,
  fetchUserProfile,
  updateSignature,
  type UserProfile,
} from '@/core/api';

import { fetchNga } from './nga-client';

export const userProfileQueryKey = (uid: number) => ['user-profile', uid] as const;

/**
 * 一个人的资料(CONTEXT.md「账号」之外的公开信息)。
 *
 * **头像缺失时补一次查询**:`ucp get` 对不少账号的 `avatar` 是空串,但
 * `ucp get_avatar` 还给得出来(API 文档 §11.2)。补查失败不影响这份资料——
 * 拿不到就让 UI 走首字占位,不该为一张头像把整页变成错误页。
 *
 * 资料不常变,`staleTime` 给足:从楼层反复点进同一个人不该反复打 ucp(ADR-0002)。
 */
export function useUserProfile(uid: number): UseQueryResult<UserProfile> {
  return useQuery({
    queryKey: userProfileQueryKey(uid),
    queryFn: async ({ signal }) => {
      const profile = await fetchUserProfile(fetchNga, { uid, signal });
      if (profile.avatarUrl !== undefined) return profile;

      try {
        const avatarUrl = await fetchUserAvatar(fetchNga, { uid, signal });
        return avatarUrl === undefined ? profile : { ...profile, avatarUrl };
      } catch {
        return profile;
      }
    },
    staleTime: 5 * 60 * 1000,
    enabled: Number.isFinite(uid) && uid > 0,
  });
}

/**
 * 改自己的签名(23 票)。写完**重拉一次资料**而不是就地改缓存:
 * 票面的验收项是「保存后回读一致」,只有服务端存下来的那一份说了算
 * (提交要过实体转义,存进去与读回来是否对得上正是这里要验的东西)。
 */
export function useUpdateSignature(uid: number): (signature: string) => Promise<void> {
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: (signature: string) => updateSignature(fetchNga, { uid, signature }),
    onSuccess: () => client.invalidateQueries({ queryKey: userProfileQueryKey(uid) }),
  });
  return (signature) => mutation.mutateAsync(signature).then(() => undefined);
}
