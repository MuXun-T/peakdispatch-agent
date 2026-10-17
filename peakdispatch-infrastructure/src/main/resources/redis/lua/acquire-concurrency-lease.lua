local time = redis.call('TIME')
local now = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
local limit = tonumber(ARGV[1])
local leaseMs = tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
local count = redis.call('ZCARD', KEYS[1])
if count >= limit then
  local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  if #first == 2 then return math.max(1, tonumber(first[2]) - now) end
  return leaseMs
end
redis.call('ZADD', KEYS[1], now + leaseMs, ARGV[3])
redis.call('PEXPIRE', KEYS[1], leaseMs + 1000)
return 0
