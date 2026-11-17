-- KEYS[1]=남은 수량, KEYS[2]=받은 회원 Set
-- ARGV[1]=memberId, ARGV[2]=1이면 Set에서도 제거(실제로 받은 회원이면 0)
-- 재고 키가 사라졌으면 INCR이 키를 1로 만들어 버리므로 있을 때만 되돌린다.
if redis.call('EXISTS', KEYS[1]) == 1 then
  redis.call('INCR', KEYS[1])
end
if ARGV[2] == '1' then
  redis.call('SREM', KEYS[2], ARGV[1])
end
return 1
