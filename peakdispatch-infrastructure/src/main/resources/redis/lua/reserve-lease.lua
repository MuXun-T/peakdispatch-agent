local function serverMillis()
    local time = redis.call('TIME')
    return time[1] * 1000 + math.floor(time[2] / 1000)
end
local now = serverMillis()
local expired = redis.call('ZRANGEBYSCORE', KEYS[4], '-inf', now, 'LIMIT', 0, 100)
for _, offerId in ipairs(expired) do
    redis.call('HDEL', KEYS[3], offerId)
    redis.call('ZREM', KEYS[4], offerId)
end
if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
if redis.call('HGET', KEYS[1], 'online') ~= '1'
   or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[7]
   or redis.call('HGET', KEYS[1], 'sequence') ~= ARGV[8]
   or redis.call('HGET', KEYS[1], 'region') ~= ARGV[6] then return -2 end
local heartbeat = tonumber(redis.call('HGET', KEYS[1], 'heartbeatMs') or '0')
if heartbeat == 0 or now - heartbeat >= tonumber(ARGV[4]) then return -3 end
if redis.call('HGET', KEYS[2], 'ready') ~= '1' then return -4 end
if redis.call('HGET', KEYS[2], 'version') ~= ARGV[9]
   or redis.call('HGET', KEYS[2], 'limit') ~= ARGV[3] then return -5 end
local existing = redis.call('HGET', KEYS[3], ARGV[1])
if existing then
    local existingToken = string.match(existing, '^([^|]+)|')
    if existingToken == ARGV[2] then return 2 end
    return -6
end
local committed = tonumber(redis.call('HGET', KEYS[2], 'committed') or '-1')
local reserved = tonumber(redis.call('HGET', KEYS[2], 'reserved') or '-1')
local limit = tonumber(ARGV[3])
if committed < 0 or reserved < 0 or limit <= 0 then return -4 end
if committed + reserved + redis.call('ZCARD', KEYS[4]) >= limit then return -7 end
local expires = now + tonumber(ARGV[5])
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2] .. '|' .. expires)
redis.call('ZADD', KEYS[4], expires, ARGV[1])
return 1
