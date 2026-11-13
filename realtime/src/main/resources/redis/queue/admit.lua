local waiting = KEYS[1]
local seen = KEYS[2]
local admittedPrefix = KEYS[3]
local cutoff = tonumber(ARGV[1])
local ttl = ARGV[2]
local batchSize = tonumber(ARGV[3])
local candidates = redis.call('ZRANGE', waiting, 0, batchSize * 4 - 1)
local admitted = {}
local count = 0
local inspected = 0
local missing = 0
local expired = 0

for _, memberId in ipairs(candidates) do
    if count >= batchSize then break end
    inspected = inspected + 1
    local lastSeen = redis.call('ZSCORE', seen, memberId)
    if not lastSeen or tonumber(lastSeen) < cutoff then
        local removed = redis.call('ZREM', waiting, memberId)
        redis.call('ZREM', seen, memberId)
        if not lastSeen then missing = missing + removed else expired = expired + removed end
    else
        count = count + 1
        local token = ARGV[3 + count]
        redis.call('ZREM', waiting, memberId)
        redis.call('ZREM', seen, memberId)
        -- 접두사와 대기열 키가 같은 회차 해시 태그를 공유하므로 같은 슬롯이다.
        redis.call('SET', admittedPrefix .. memberId, token, 'PX', ttl)
        table.insert(admitted, memberId)
        table.insert(admitted, token)
    end
end
return {inspected, missing, expired, admitted}
